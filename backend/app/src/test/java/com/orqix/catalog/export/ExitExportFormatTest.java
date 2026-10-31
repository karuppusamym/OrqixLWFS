package com.orqix.catalog.export;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.zip.ZipInputStream;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Unit checks of the exit archive building blocks: CSV quoting, safe paths, per-file hashes, secret scrubbing. */
class ExitExportFormatTest {

    @Test
    void csvQuotesOnlyWhenNeeded() throws Exception {
        StringWriter out = new StringWriter();
        Csv.row(out, Arrays.asList("plain", "a,b", "say \"hi\"", "two\nlines", null, ""));
        assertThat(out.toString()).isEqualTo("plain,\"a,b\",\"say \"\"hi\"\"\",\"two\nlines\",,\r\n");
    }

    @Test
    void pathsStayInsideTheArchive() {
        assertThat(ArchiveWriter.safePath("workflows/a-b/releases/1/decisions/x.dmn")).isTrue();
        assertThat(ArchiveWriter.safePath("../etc/passwd")).isFalse();
        assertThat(ArchiveWriter.safePath("a/../../b")).isFalse();
        assertThat(ArchiveWriter.safePath("/abs")).isFalse();
        assertThat(ArchiveWriter.safePath("a\b")).isFalse();
        assertThat(ArchiveWriter.safePath("a//b")).isFalse();
    }

    @Test
    void entriesAreHashedWhileStreamed() throws Exception {
        ByteArrayOutputStream target = new ByteArrayOutputStream();
        ArchiveWriter archive = new ArchiveWriter(target);
        byte[] big = new byte[3 * 1024 * 1024];
        Arrays.fill(big, (byte) 'x');
        archive.text("a.txt", "hello");
        archive.entry("big.bin", out -> {
            for (int i = 0; i < big.length; i += 8192) {
                out.write(big, i, Math.min(8192, big.length - i));
            }
        });
        assertThatThrownBy(() -> archive.text("a.txt", "again")).isInstanceOf(IllegalArgumentException.class);
        archive.finish();
        List<ArchiveWriter.FileEntry> entries = archive.manifestEntries();
        assertThat(entries).extracting(ArchiveWriter.FileEntry::path).containsExactly("a.txt", "big.bin");
        assertThat(entries.get(0).sha256()).isEqualTo(sha256("hello".getBytes(StandardCharsets.UTF_8)));
        assertThat(entries.get(1).sha256()).isEqualTo(sha256(big));
        assertThat(entries.get(1).bytes()).isEqualTo(big.length);
        assertThat(archive.archiveSha256()).isEqualTo(sha256(target.toByteArray()));
        assertThat(archive.archiveBytes()).isEqualTo(target.size());
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(target.toByteArray()))) {
            assertThat(zip.getNextEntry().getName()).isEqualTo("a.txt");
            assertThat(new String(zip.readAllBytes(), StandardCharsets.UTF_8)).isEqualTo("hello");
            assertThat(zip.getNextEntry().getName()).isEqualTo("big.bin");
            assertThat(zip.readAllBytes()).isEqualTo(big);
        }
    }

    @Test
    void secretLookingFieldsAreRemoved() {
        JsonNode node = JsonMapper.builder().build().readTree("""
                {"kind":"http","auth":{"type":"bearerToken","secretRef":"x","nested":[{"password":"p","keep":1}]},
                 "token":{"structured":true}}""");
        assertThat(WorkspaceExportService.scrub(node)).isEqualTo(2);
        assertThat(node.toString()).doesNotContain("secretRef").doesNotContain("password").contains("\"keep\":1")
                .contains("bearerToken").contains("structured");
    }

    private static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
}
