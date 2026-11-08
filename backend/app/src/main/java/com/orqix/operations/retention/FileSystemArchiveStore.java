package com.orqix.operations.retention;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Optional;

/**
 * Archives on a mounted file system (a persistent volume in Kubernetes, a share elsewhere). Writes go to a temporary
 * file in the target directory and are moved into place, so a reader never sees half an archive.
 */
public class FileSystemArchiveStore implements ArchiveStore {

    private final Path root;

    public FileSystemArchiveStore(Path root) {
        this.root = root.toAbsolutePath().normalize();
    }

    @Override
    public String kind() {
        return "filesystem";
    }

    @Override
    public void put(String key, byte[] bytes, String sha256) {
        Path target = resolve(key);
        try {
            Files.createDirectories(target.getParent());
            Path temp = Files.createTempFile(target.getParent(), ".part-", ".tmp");
            try {
                Files.write(temp, bytes);
                try {
                    Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                } catch (AtomicMoveNotSupportedException e) {
                    Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
                }
            } finally {
                Files.deleteIfExists(temp);
            }
        } catch (IOException e) {
            throw new ArchiveStoreException("The archive could not be written to the file system", e);
        }
    }

    @Override
    public Optional<byte[]> get(String key) {
        try {
            return Optional.of(Files.readAllBytes(resolve(key)));
        } catch (NoSuchFileException e) {
            return Optional.empty();
        } catch (IOException e) {
            throw new ArchiveStoreException("The archive could not be read from the file system", e);
        }
    }

    private Path resolve(String key) {
        Path path = root.resolve(ArchiveStore.checkKey(key)).normalize();
        if (!path.startsWith(root)) {
            throw new IllegalArgumentException("Invalid archive key");
        }
        return path;
    }
}
