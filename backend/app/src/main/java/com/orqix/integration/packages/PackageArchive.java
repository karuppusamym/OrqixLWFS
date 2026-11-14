package com.orqix.integration.packages;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import com.orqix.shared.json.Json;

/**
 * The {@code .orqpkg} container (CG-C03, docs/build/S133 section 3): a ZIP of UTF-8 files plus {@code checksums.sha256}
 * (one {@code <sha256>  <path>} line per file, sorted by path) and {@code signature.jws}, a compact JWS (EdDSA, Ed25519)
 * whose payload is exactly the bytes of {@code checksums.sha256}.
 *
 * <p>Reading is defensive, as for migration archives: at most {@link #MAX_ARCHIVE_BYTES} compressed and
 * {@link #MAX_TOTAL_BYTES} uncompressed, {@link #MAX_ENTRIES} files of at most {@link #MAX_FILE_BYTES}, a bounded
 * compression ratio, only relative paths in the known (lower-case) folders (no {@code ..}, backslash, absolute path,
 * drive letter, control character or duplicate), no directory entries other than the known folders, and a checksum
 * line for every file and a file for every checksum line. Nothing is written to disk.
 */
public final class PackageArchive {

    public static final int MAX_ARCHIVE_BYTES = 5 * 1024 * 1024;
    public static final int MAX_TOTAL_BYTES = 10 * 1024 * 1024;
    public static final int MAX_FILE_BYTES = 1024 * 1024;
    public static final int MAX_ENTRIES = 200;
    public static final int MAX_RATIO = 100;
    public static final String MANIFEST = "manifest.json";
    public static final String CHECKSUMS = "checksums.sha256";
    public static final String SIGNATURE = "signature.jws";
    static final String JWS_TYPE = "orqix-package+jws";

    private static final Pattern FILE = Pattern.compile(
            "^(manifest\\.json|checksums\\.sha256|signature\\.jws|(connections|triggers|templates/steps|templates/workflows"
                    + "|fixtures|docs)/[A-Za-z0-9][A-Za-z0-9._-]{0,80}(/[A-Za-z0-9][A-Za-z0-9._-]{0,80})?)$");
    private static final Pattern DIRECTORY = Pattern.compile(
            "^(connections|triggers|templates|templates/steps|templates/workflows|fixtures|docs)(/[A-Za-z0-9][A-Za-z0-9._-]{0,80})?/$");
    private static final Pattern CHECKSUM_LINE = Pattern.compile("^([0-9a-f]{64})  (\\S+)$");
    /** Fixed entry time so the same files always give the same bytes (and the same package hash). */
    /** Fixed DOS time of every entry, written as local date-time so the archive does not depend on the time zone. */
    private static final java.time.LocalDateTime ENTRY_TIME = java.time.LocalDateTime.of(2026, 1, 1, 0, 0);

    /** A broken archive rule; {@code path} names the file when there is one (never file content). */
    public static final class Invalid extends RuntimeException {
        private final String code;
        private final String path;

        public Invalid(String code, String path, String message) {
            super(message, null, false, false);
            this.code = code;
            this.path = path;
        }

        public String code() {
            return code;
        }

        public String path() {
            return path;
        }
    }

    /**
     * @param files     every content file by path (without checksums and signature), in path order
     * @param checksums the checksum file's bytes (the signed payload)
     * @param signature the compact JWS, or null when the archive is unsigned
     * @param sha256    hex SHA-256 of the whole archive (what an approver approves)
     */
    public record Archive(Map<String, byte[]> files, byte[] checksums, String signature, String sha256) {

        public String text(String path) {
            byte[] bytes = files.get(path);
            return bytes == null ? null : new String(bytes, StandardCharsets.UTF_8);
        }
    }

    /** Signature state of an archive. {@code keyId} is the JWS key id (null when unsigned). */
    public enum SignatureState { VERIFIED, UNSIGNED, UNTRUSTED_KEY, INVALID }

    public record Verification(SignatureState state, String keyId) {
    }

    private PackageArchive() {
    }

    // ------------------------------------------------------------------------------------------------- reading

    public static Archive read(byte[] archive) {
        if (archive == null || archive.length == 0) {
            throw new Invalid("PKG_ARCHIVE", null, "Upload a .orqpkg file.");
        }
        if (archive.length > MAX_ARCHIVE_BYTES) {
            throw new Invalid("PKG_ARCHIVE_TOO_LARGE", null, "A package can be at most 5 MiB.");
        }
        Map<String, byte[]> entries = new TreeMap<>();
        long total = 0;
        int count = 0;
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(archive), StandardCharsets.UTF_8)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                String name = entry.getName();
                if (entry.isDirectory()) {
                    if (!DIRECTORY.matcher(name).matches()) {
                        throw new Invalid("PKG_PATH", safe(name), "The folder " + safe(name) + " is not part of the package format.");
                    }
                    continue;
                }
                if (++count > MAX_ENTRIES) {
                    throw new Invalid("PKG_ARCHIVE_TOO_LARGE", null, "A package can hold at most " + MAX_ENTRIES + " files.");
                }
                if (!FILE.matcher(name).matches() || name.contains("..")) {
                    throw new Invalid("PKG_PATH", safe(name), "The file " + safe(name) + " has a path outside the package"
                            + " format (relative paths in the known folders only).");
                }
                if (entries.containsKey(name)) {
                    throw new Invalid("PKG_PATH", name, "The file " + name + " appears twice.");
                }
                byte[] content = readLimited(zip, name);
                total += content.length;
                if (total > MAX_TOTAL_BYTES || (long) archive.length * MAX_RATIO < total) {
                    throw new Invalid("PKG_ARCHIVE_TOO_LARGE", null, "The package unpacks to more than allowed.");
                }
                entries.put(name, content);
            }
        } catch (IOException | IllegalArgumentException e) {
            throw new Invalid("PKG_ARCHIVE", null, "The file is not a readable package archive.");
        }
        if (!entries.containsKey(MANIFEST)) {
            throw new Invalid("PKG_ARCHIVE", MANIFEST, "The package has no manifest.json.");
        }
        byte[] checksums = entries.remove(CHECKSUMS);
        if (checksums == null) {
            throw new Invalid("PKG_CHECKSUMS", CHECKSUMS, "The package has no checksums.sha256.");
        }
        byte[] signature = entries.remove(SIGNATURE);
        checkChecksums(entries, checksums);
        return new Archive(java.util.Collections.unmodifiableMap(new LinkedHashMap<>(entries)), checksums,
                signature == null ? null : new String(signature, StandardCharsets.UTF_8).strip(), Json.sha256Hex(archive));
    }

    private static byte[] readLimited(InputStream in, String name) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int n;
        while ((n = in.read(buffer)) > 0) {
            if (out.size() + n > MAX_FILE_BYTES) {
                throw new Invalid("PKG_ARCHIVE_TOO_LARGE", name, "The file " + name + " is larger than 1 MiB.");
            }
            out.write(buffer, 0, n);
        }
        return out.toByteArray();
    }

    private static void checkChecksums(Map<String, byte[]> files, byte[] checksums) {
        String text = new String(checksums, StandardCharsets.UTF_8);
        Map<String, String> listed = new LinkedHashMap<>();
        for (String line : text.split("\n")) {
            if (line.isEmpty()) {
                continue;
            }
            Matcher m = CHECKSUM_LINE.matcher(line);
            if (!m.matches()) {
                throw new Invalid("PKG_CHECKSUMS", CHECKSUMS, "checksums.sha256 has a line that is not '<sha256>  <path>'.");
            }
            if (listed.put(m.group(2), m.group(1)) != null) {
                throw new Invalid("PKG_CHECKSUMS", CHECKSUMS, "checksums.sha256 lists " + safe(m.group(2)) + " twice.");
            }
        }
        for (Map.Entry<String, byte[]> file : files.entrySet()) {
            String expected = listed.remove(file.getKey());
            if (expected == null) {
                throw new Invalid("PKG_CHECKSUMS", file.getKey(), "checksums.sha256 does not list " + file.getKey() + ".");
            }
            if (!expected.equals(Json.sha256Hex(file.getValue()))) {
                throw new Invalid("PKG_CHECKSUMS", file.getKey(), "The checksum of " + file.getKey() + " does not match.");
            }
        }
        if (!listed.isEmpty()) {
            throw new Invalid("PKG_CHECKSUMS", CHECKSUMS, "checksums.sha256 lists " + safe(listed.keySet().iterator().next())
                    + ", which is not in the package.");
        }
    }

    /** Verifies the signature against the trusted key of its key id; never throws for a bad signature. */
    public static Verification verify(Archive archive, PackageSettings settings) {
        if (archive.signature() == null || archive.signature().isEmpty()) {
            return new Verification(SignatureState.UNSIGNED, null);
        }
        String[] parts = archive.signature().split("\\.", -1);
        if (parts.length != 3) {
            return new Verification(SignatureState.INVALID, null);
        }
        String keyId;
        try {
            String header = new String(Base64.getUrlDecoder().decode(parts[0]), StandardCharsets.UTF_8);
            tools.jackson.databind.JsonNode h = tools.jackson.databind.json.JsonMapper.builder().build().readTree(header);
            if (!"EdDSA".equals(h.path("alg").asString()) || !JWS_TYPE.equals(h.path("typ").asString())
                    || h.has("crit") || !h.path("kid").isString()) {
                return new Verification(SignatureState.INVALID, null);
            }
            keyId = h.path("kid").asString();
            byte[] payload = Base64.getUrlDecoder().decode(parts[1]);
            if (!java.security.MessageDigest.isEqual(payload, archive.checksums())) {
                return new Verification(SignatureState.INVALID, keyId);
            }
            PublicKey key = settings.trusted(keyId);
            if (key == null) {
                return new Verification(SignatureState.UNTRUSTED_KEY, keyId);
            }
            Signature verifier = Signature.getInstance("Ed25519");
            verifier.initVerify(key);
            verifier.update((parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII));
            return new Verification(verifier.verify(Base64.getUrlDecoder().decode(parts[2])) ? SignatureState.VERIFIED
                    : SignatureState.INVALID, keyId);
        } catch (GeneralSecurityException | RuntimeException e) {
            return new Verification(SignatureState.INVALID, null);
        }
    }

    // ------------------------------------------------------------------------------------------------- writing

    /**
     * Builds a package from its content files: writes {@code checksums.sha256}, signs it when a key is given and zips
     * everything in path order with fixed times, so the same input always gives byte-identical output (Ed25519
     * signatures are deterministic).
     */
    public static byte[] build(Map<String, byte[]> files, PrivateKey key, String keyId) {
        Map<String, byte[]> sorted = new TreeMap<>(files);
        if (sorted.containsKey(CHECKSUMS) || sorted.containsKey(SIGNATURE) || !sorted.containsKey(MANIFEST)) {
            throw new IllegalArgumentException("Give the content files with manifest.json and without checksums or signature.");
        }
        StringBuilder checksums = new StringBuilder();
        sorted.forEach((path, bytes) -> checksums.append(Json.sha256Hex(bytes)).append("  ").append(path).append('\n'));
        byte[] checksumBytes = checksums.toString().getBytes(StandardCharsets.UTF_8);
        Map<String, byte[]> all = new TreeMap<>(sorted);
        all.put(CHECKSUMS, checksumBytes);
        if (key != null) {
            all.put(SIGNATURE, sign(checksumBytes, key, keyId).getBytes(StandardCharsets.US_ASCII));
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out, StandardCharsets.UTF_8)) {
            zip.setLevel(9);
            List<String> names = new ArrayList<>(all.keySet());
            for (String name : names) {
                ZipEntry entry = new ZipEntry(name);
                entry.setTimeLocal(ENTRY_TIME);
                zip.putNextEntry(entry);
                zip.write(all.get(name));
                zip.closeEntry();
            }
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        return out.toByteArray();
    }

    /** @return compact JWS (EdDSA) over the payload */
    public static String sign(byte[] payload, PrivateKey key, String keyId) {
        Base64.Encoder b64 = Base64.getUrlEncoder().withoutPadding();
        String header = b64.encodeToString(("{\"alg\":\"EdDSA\",\"kid\":\"" + keyId + "\",\"typ\":\"" + JWS_TYPE + "\"}")
                .getBytes(StandardCharsets.UTF_8));
        String body = b64.encodeToString(payload);
        try {
            Signature signer = Signature.getInstance("Ed25519");
            signer.initSign(key);
            signer.update((header + "." + body).getBytes(StandardCharsets.US_ASCII));
            return header + "." + body + "." + b64.encodeToString(signer.sign());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Signing failed", e);
        }
    }

    /** A path for messages: printable, short. */
    static String safe(String name) {
        String printable = name == null ? "" : name.replaceAll("[^A-Za-z0-9._/ -]", "?");
        return printable.length() > 100 ? printable.substring(0, 100) + "…" : printable;
    }

    /** Hex helper for tests and tools. */
    public static String hex(byte[] bytes) {
        return HexFormat.of().formatHex(bytes);
    }
}
