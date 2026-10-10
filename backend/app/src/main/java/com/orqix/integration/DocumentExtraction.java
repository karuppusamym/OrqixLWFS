package com.orqix.integration;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarFile;

import org.springframework.stereotype.Component;

/** Separate parser JVM: bounded heap, bounded output, bounded concurrency, and forced deadline termination. */
@Component
public final class DocumentExtraction {
    public static final int MAX_FILE_BYTES = 5 * 1024 * 1024;
    public static final int MAX_TEXT_BYTES = 64 * 1024;
    private static final Semaphore SLOTS = new Semaphore(2);

    public String extract(byte[] bytes, String mime, long deadline) {
        if (bytes == null || bytes.length == 0 || bytes.length > MAX_FILE_BYTES) { throw failure("DOCUMENT_CONTENT_TOO_LARGE"); }
        String type = DocumentExtractionWorker.type(mime);
        Path directory = null;
        Process worker = null;
        boolean acquired = false;
        try {
            long remaining = Math.max(0, deadline - System.nanoTime());
            acquired = SLOTS.tryAcquire(Math.min(remaining, TimeUnit.SECONDS.toNanos(1)), TimeUnit.NANOSECONDS);
            if (!acquired) { throw new MicrosoftGraphDocuments.Failure("DOCUMENT_EXTRACTION_BUSY", null, HttpExecutor.Observation.NOT_SENT); }
            directory = Files.createTempDirectory("orqix-document-");
            Path source = directory.resolve("source.bin"), output = directory.resolve("text.txt");
            Files.write(source, bytes);
            List<String> command = workerCommand(type, source, output);
            worker = new ProcessBuilder(command).redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD).start();
            remaining = deadline - System.nanoTime();
            if (remaining <= 0 || !worker.waitFor(remaining, TimeUnit.NANOSECONDS)) {
                worker.destroyForcibly();
                worker.waitFor(2, TimeUnit.SECONDS);
                throw failure("DOCUMENT_EXTRACTION_TIMEOUT");
            }
            if (worker.exitValue() != 0) {
                throw failure(switch (worker.exitValue()) {
                    case 3 -> "DOCUMENT_EXTRACTION_LIMIT_EXCEEDED";
                    case 4 -> "DOCUMENT_ENCRYPTED";
                    case 5 -> "DOCUMENT_TEXT_UNAVAILABLE";
                    default -> "DOCUMENT_EXTRACTION_INVALID";
                });
            }
            if (!Files.isRegularFile(output) || Files.size(output) > MAX_TEXT_BYTES) { throw failure("DOCUMENT_EXTRACTION_LIMIT_EXCEEDED"); }
            return Files.readString(output, StandardCharsets.UTF_8);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw failure("DOCUMENT_EXTRACTION_INTERRUPTED");
        } catch (IOException e) {
            throw failure("DOCUMENT_EXTRACTION_UNAVAILABLE");
        } finally {
            if (worker != null && worker.isAlive()) { worker.destroyForcibly(); }
            if (directory != null) {
                for (String name : List.of("source.bin", "text.txt")) {
                    try { Files.deleteIfExists(directory.resolve(name)); } catch (IOException ignored) { }
                }
                try { Files.deleteIfExists(directory); } catch (IOException ignored) { }
            }
            if (acquired) { SLOTS.release(); }
        }
    }

    static List<String> workerCommand(String type, Path source, Path output) {
        String executable = Path.of(System.getProperty("java.home"), "bin", System.getProperty("os.name")
                .toLowerCase().contains("windows") ? "java.exe" : "java").toString();
        String classpath = System.getProperty("java.class.path");
        List<String> command = new ArrayList<>(List.of(executable, "-Xmx128m", "-XX:MaxMetaspaceSize=64m",
                "-Djava.awt.headless=true", "-Djava.io.tmpdir=" + source.getParent(), "-cp", classpath));
        // Executable Spring Boot jars keep application classes under BOOT-INF; PropertiesLauncher resolves them.
        if (isBootJar(classpath)) {
            command.add(1, "-Dloader.main=" + DocumentExtractionWorker.class.getName());
            command.add("org.springframework.boot.loader.launch.PropertiesLauncher");
        } else {
            command.add(DocumentExtractionWorker.class.getName());
        }
        command.addAll(List.of(type, source.toString(), output.toString()));
        return command;
    }

    private static boolean isBootJar(String classpath) {
        if (classpath.contains(java.io.File.pathSeparator)) { return false; }
        try (JarFile jar = new JarFile(classpath)) { return jar.getEntry("BOOT-INF/classes/") != null; }
        catch (IOException e) { return false; }
    }

    private static MicrosoftGraphDocuments.Failure failure(String code) {
        return new MicrosoftGraphDocuments.Failure(code, null, HttpExecutor.Observation.CLIENT_ERROR);
    }
}
