package com.orqix.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.Test;

import com.orqix.integration.packages.PackageArchive;
import com.orqix.integration.packages.PackageSettings;
import com.orqix.support.ExamplePublisher;

/**
 * The .orqpkg container (CG-C03, docs/build/S133 section 3): the committed ServiceNow package is exactly what its
 * source folder builds to; reading refuses path traversal, unknown folders, duplicates, unlisted or changed files and
 * oversize content; signatures verify only with a trusted key and only over the unchanged checksums.
 */
class ConnectorPackageArchiveTest {

    private static final Path SOURCE = ExamplePublisher.repoRoot().resolve("examples/packages/servicenow-itsm");
    private static final Path BUILT = ExamplePublisher.repoRoot().resolve("examples/packages/servicenow-itsm-1.0.0.orqpkg");
    private static final PackageSettings TRUSTING = new PackageSettings(
            Map.of(ExamplePublisher.KEY_ID, ExamplePublisher.publicKeyBase64()), List.of(), null, null, null);
    private static final PackageSettings TRUSTING_NOBODY = new PackageSettings(Map.of(), List.of(), null, null, null);

    @Test
    void theCommittedServiceNowPackageIsExactlyWhatItsSourceBuildsTo() throws Exception {
        byte[] built = ExamplePublisher.build(SOURCE);
        if (Boolean.getBoolean("orqix.packages.write") || !Files.exists(BUILT)) {
            Files.write(BUILT, built); // -Dorqix.packages.write=true rebuilds the committed file after a source change
        }
        assertThat(built).as("rebuild with -Dorqix.packages.write=true after changing examples/packages/servicenow-itsm")
                .isEqualTo(Files.readAllBytes(BUILT));
        assertThat(ExamplePublisher.build(SOURCE)).as("deterministic").isEqualTo(built);
        PackageArchive.Archive archive = PackageArchive.read(built);
        assertThat(archive.files()).containsKeys("manifest.json", "docs/README.md",
                "connections/servicenow-itsm.connection.json", "triggers/incident-updated.webhook.json");
        assertThat(PackageArchive.verify(archive, TRUSTING).state()).isEqualTo(PackageArchive.SignatureState.VERIFIED);
        assertThat(PackageArchive.verify(archive, TRUSTING_NOBODY).state())
                .isEqualTo(PackageArchive.SignatureState.UNTRUSTED_KEY);
    }

    @Test
    void aChangedFileOrChangedChecksumsNeverVerify() throws Exception {
        Map<String, byte[]> files = ExamplePublisher.sources(SOURCE);
        byte[] signed = ExamplePublisher.build(files);
        // Changing a file without updating checksums is caught by the checksums.
        Map<String, byte[]> entries = unzip(signed);
        entries.put("docs/README.md", "changed".getBytes(StandardCharsets.UTF_8));
        assertThatThrownBy(() -> PackageArchive.read(zip(entries))).isInstanceOf(PackageArchive.Invalid.class)
                .hasFieldOrPropertyWithValue("code", "PKG_CHECKSUMS");
        // Rebuilding the checksums but keeping the old signature is caught by the signature.
        Map<String, byte[]> changed = new TreeMap<>(files);
        changed.put("docs/README.md", "changed".getBytes(StandardCharsets.UTF_8));
        Map<String, byte[]> resigned = unzip(PackageArchive.build(changed, null, null));
        resigned.put(PackageArchive.SIGNATURE, unzip(signed).get(PackageArchive.SIGNATURE));
        PackageArchive.Archive tampered = PackageArchive.read(zip(resigned));
        assertThat(PackageArchive.verify(tampered, TRUSTING).state()).isEqualTo(PackageArchive.SignatureState.INVALID);
        // A signature by another key under the trusted key id does not verify.
        byte[] forged = PackageArchive.build(files, ExamplePublisher.untrusted().getPrivate(), ExamplePublisher.KEY_ID);
        assertThat(PackageArchive.verify(PackageArchive.read(forged), TRUSTING).state())
                .isEqualTo(PackageArchive.SignatureState.INVALID);
        // Unsigned.
        assertThat(PackageArchive.verify(PackageArchive.read(PackageArchive.build(files, null, null)), TRUSTING).state())
                .isEqualTo(PackageArchive.SignatureState.UNSIGNED);
        // Algorithm "none" in the header is refused.
        String none = Base64.getUrlEncoder().withoutPadding().encodeToString(
                ("{\"alg\":\"none\",\"kid\":\"" + ExamplePublisher.KEY_ID + "\",\"typ\":\"orqix-package+jws\"}").getBytes(StandardCharsets.UTF_8));
        Map<String, byte[]> noneEntries = unzip(signed);
        String jws = new String(noneEntries.get(PackageArchive.SIGNATURE), StandardCharsets.US_ASCII);
        noneEntries.put(PackageArchive.SIGNATURE, (none + jws.substring(jws.indexOf('.')) ).getBytes(StandardCharsets.US_ASCII));
        assertThat(PackageArchive.verify(PackageArchive.read(zip(noneEntries)), TRUSTING).state())
                .isEqualTo(PackageArchive.SignatureState.INVALID);
    }

    @Test
    void unsafePathsAndOversizeContentAreRefused() throws Exception {
        Map<String, byte[]> base = unzip(ExamplePublisher.build(SOURCE));
        for (String path : List.of("../evil.json", "/etc/passwd", "connections/../../x.json", "C:/x.json",
                "connections\\x.json", "scripts/run.js", "Connections/x.json", "docs/a/b/c.md")) {
            Map<String, byte[]> entries = new LinkedHashMap<>(base);
            entries.put(path, "{}".getBytes(StandardCharsets.UTF_8));
            assertThatThrownBy(() -> PackageArchive.read(zip(entries))).as(path).isInstanceOf(PackageArchive.Invalid.class)
                    .hasFieldOrPropertyWithValue("code", "PKG_PATH");
        }
        // A file that unpacks beyond 1 MiB.
        Map<String, byte[]> big = new LinkedHashMap<>(base);
        big.put("docs/big.md", new byte[PackageArchive.MAX_FILE_BYTES + 1]);
        assertThatThrownBy(() -> PackageArchive.read(zip(big))).isInstanceOf(PackageArchive.Invalid.class)
                .hasFieldOrPropertyWithValue("code", "PKG_ARCHIVE_TOO_LARGE");
        // A file listed nowhere in the checksums.
        Map<String, byte[]> extra = new LinkedHashMap<>(base);
        extra.put("docs/extra.md", "x".getBytes(StandardCharsets.UTF_8));
        assertThatThrownBy(() -> PackageArchive.read(zip(extra))).isInstanceOf(PackageArchive.Invalid.class)
                .hasFieldOrPropertyWithValue("code", "PKG_CHECKSUMS");
        // Not a ZIP at all.
        assertThatThrownBy(() -> PackageArchive.read("not a zip".getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(PackageArchive.Invalid.class);
    }

    static Map<String, byte[]> unzip(byte[] archive) throws Exception {
        Map<String, byte[]> out = new LinkedHashMap<>();
        try (var zip = new java.util.zip.ZipInputStream(new java.io.ByteArrayInputStream(archive))) {
            ZipEntry e;
            while ((e = zip.getNextEntry()) != null) {
                out.put(e.getName(), zip.readAllBytes());
            }
        }
        return out;
    }

    static byte[] zip(Map<String, byte[]> entries) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            for (Map.Entry<String, byte[]> e : entries.entrySet()) {
                zip.putNextEntry(new ZipEntry(e.getKey()));
                zip.write(e.getValue());
                zip.closeEntry();
            }
        }
        return out.toByteArray();
    }
}
