package com.orqix.support;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.spec.NamedParameterSpec;
import java.util.Base64;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;

import com.orqix.integration.packages.PackageArchive;

/**
 * The example connector-package publisher of the repository (docs/build/S133): an Ed25519 key pair derived from a fixed,
 * published seed, so the committed {@code examples/packages/*.orqpkg} files can be rebuilt byte for byte. Anyone can
 * sign with it: it is trusted only by the test configuration and must never be trusted by a deployment.
 */
public final class ExamplePublisher {

    public static final String KEY_ID = "orqix-example-2026";
    private static final KeyPair PAIR = derive("orqix-example-publisher-2026 (published; never trust in production)");
    private static final KeyPair OTHER = derive("an untrusted publisher");

    private ExamplePublisher() {
    }

    public static KeyPair keyPair() {
        return PAIR;
    }

    /** A different key that no configuration trusts (for the untrusted-signature cases). */
    public static KeyPair untrusted() {
        return OTHER;
    }

    /** Base64 X.509 public key for {@code orqix.connector-packages.trusted-keys.orqix-example-2026}. */
    public static String publicKeyBase64() {
        return Base64.getEncoder().encodeToString(PAIR.getPublic().getEncoded());
    }

    private static KeyPair derive(String seedText) {
        try {
            byte[] seed = MessageDigest.getInstance("SHA-256").digest(seedText.getBytes(StandardCharsets.UTF_8));
            SecureRandom fixed = new SecureRandom() {
                @Override
                public void nextBytes(byte[] bytes) {
                    System.arraycopy(seed, 0, bytes, 0, Math.min(seed.length, bytes.length));
                }
            };
            KeyPairGenerator generator = KeyPairGenerator.getInstance("Ed25519");
            generator.initialize(NamedParameterSpec.ED25519, fixed);
            return generator.generateKeyPair();
        } catch (NoSuchAlgorithmException | java.security.InvalidAlgorithmParameterException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Every file of a package source folder by its package path (forward slashes). */
    public static Map<String, byte[]> sources(Path folder) throws IOException {
        Map<String, byte[]> files = new TreeMap<>();
        try (Stream<Path> walk = Files.walk(folder)) {
            for (Path p : walk.filter(Files::isRegularFile).toList()) {
                String name = folder.relativize(p).toString().replace('\\', '/');
                // Normalize line endings: a checkout with CRLF must give the same package as one with LF.
                byte[] bytes = new String(Files.readAllBytes(p), StandardCharsets.UTF_8).replace("\r\n", "\n")
                        .getBytes(StandardCharsets.UTF_8);
                files.put(name, bytes);
            }
        }
        return files;
    }

    /** Builds and signs a package from a source folder with the example key. */
    public static byte[] build(Path folder) throws IOException {
        return PackageArchive.build(sources(folder), PAIR.getPrivate(), KEY_ID);
    }

    /** Builds and signs a package from files with the example key. */
    public static byte[] build(Map<String, byte[]> files) {
        return PackageArchive.build(files, PAIR.getPrivate(), KEY_ID);
    }

    public static Path repoRoot() {
        return Path.of(System.getProperty("orqix.repoRoot", "../.."));
    }
}
