package com.orqix.integration.secrets;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import org.springframework.stereotype.Component;

import com.orqix.shared.config.OrqixProperties;

/**
 * Mounted secrets (S6 section 6): one file per plain {@code secretRef} in {@code orqix.secrets.dir}, for example a
 * Kubernetes or OpenShift Secret volume. The file is read on every use and never cached, so a rotated Secret takes
 * effect without restart.
 */
@Component
public class FileSecretProvider implements SecretProvider {

    private final Path dir;

    @org.springframework.beans.factory.annotation.Autowired
    public FileSecretProvider(OrqixProperties properties) {
        this(properties.secrets().dir());
    }

    FileSecretProvider(Path dir) {
        this.dir = dir;
    }

    @Override
    public String scheme() {
        return SecretRef.FILE;
    }

    @Override
    public boolean enabled() {
        return true;
    }

    @Override
    public boolean cacheable() {
        return false;
    }

    @Override
    public Optional<String> resolve(SecretRef ref) {
        Path root = dir.toAbsolutePath().normalize();
        Path file = root.resolve(ref.locator()).normalize();
        if (!file.startsWith(root) || !Files.isRegularFile(file)) {
            return Optional.empty();
        }
        try {
            String value = Files.readString(file, StandardCharsets.UTF_8).strip();
            return value.isEmpty() ? Optional.empty() : Optional.of(value);
        } catch (IOException e) {
            return Optional.empty();
        }
    }
}
