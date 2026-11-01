package com.orqix.catalog.export;

import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Streams a ZIP archive entry by entry. Each entry's SHA-256 and size are computed while it is written, so nothing is
 * held in memory beyond the current buffer; {@link #manifestEntries()} lists them for {@code manifest.json}. The
 * archive's own SHA-256 is computed over the bytes that leave this writer.
 */
final class ArchiveWriter implements AutoCloseable {

    /** One written file: archive path, lowercase hex SHA-256 of its content, and its size in bytes. */
    record FileEntry(String path, String sha256, long bytes) {
    }

    /** Body of one entry; writes to the given stream and must not close it. */
    @FunctionalInterface
    interface Body {
        void write(OutputStream out) throws IOException;
    }

    private static final Pattern SAFE_PATH = Pattern.compile("^[A-Za-z0-9._-]+(/[A-Za-z0-9._-]+)*$");
    private static final LocalDateTime ENTRY_TIME = LocalDateTime.of(1980, 1, 1, 0, 0);

    private final DigestingStream archive;
    private final ZipOutputStream zip;
    private final List<FileEntry> entries = new ArrayList<>();
    private final Set<String> paths = new HashSet<>();

    ArchiveWriter(OutputStream target) {
        this.archive = new DigestingStream(target);
        this.zip = new ZipOutputStream(archive, StandardCharsets.UTF_8);
    }

    /** Rejects absolute paths, {@code ..} segments and characters outside a portable file name set. */
    static boolean safePath(String path) {
        if (path == null || path.length() > 400 || !SAFE_PATH.matcher(path).matches()) {
            return false;
        }
        for (String segment : path.split("/")) {
            if (segment.equals(".") || segment.equals("..")) {
                return false;
            }
        }
        return true;
    }

    void text(String path, String content) throws IOException {
        bytes(path, content.getBytes(StandardCharsets.UTF_8));
    }

    void bytes(String path, byte[] content) throws IOException {
        entry(path, out -> out.write(content));
    }

    void entry(String path, Body body) throws IOException {
        if (!safePath(path)) {
            throw new IllegalArgumentException("Unsafe archive path " + path);
        }
        if (!paths.add(path)) {
            throw new IllegalArgumentException("Duplicate archive path " + path);
        }
        ZipEntry zipEntry = new ZipEntry(path);
        zipEntry.setTimeLocal(ENTRY_TIME);
        zip.putNextEntry(zipEntry);
        DigestingStream content = new DigestingStream(new NonClosing(zip));
        body.write(content);
        content.flush();
        zip.closeEntry();
        entries.add(new FileEntry(path, content.hex(), content.count()));
    }

    List<FileEntry> manifestEntries() {
        return List.copyOf(entries);
    }

    /** Finishes the archive (central directory written) without closing the target stream. */
    void finish() throws IOException {
        zip.finish();
        archive.flush();
    }

    /** SHA-256 and size of the archive bytes written so far; final after {@link #finish()}. */
    String archiveSha256() {
        return archive.hex();
    }

    long archiveBytes() {
        return archive.count();
    }

    @Override
    public void close() throws IOException {
        zip.finish();
    }

    /** Counts and hashes everything written through it. */
    static final class DigestingStream extends FilterOutputStream {
        private final MessageDigest digest;
        private long count;

        DigestingStream(OutputStream out) {
            super(out);
            try {
                this.digest = MessageDigest.getInstance("SHA-256");
            } catch (NoSuchAlgorithmException e) {
                throw new IllegalStateException(e);
            }
        }

        @Override
        public void write(int b) throws IOException {
            out.write(b);
            digest.update((byte) b);
            count++;
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            out.write(b, off, len);
            digest.update(b, off, len);
            count += len;
        }

        long count() {
            return count;
        }

        String hex() {
            try {
                return HexFormat.of().formatHex(((MessageDigest) digest.clone()).digest());
            } catch (CloneNotSupportedException e) {
                throw new IllegalStateException(e);
            }
        }
    }

    /** Keeps an entry body from closing the ZIP stream. */
    private static final class NonClosing extends FilterOutputStream {
        NonClosing(OutputStream out) {
            super(out);
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            out.write(b, off, len);
        }

        @Override
        public void close() throws IOException {
            out.flush();
        }
    }
}
