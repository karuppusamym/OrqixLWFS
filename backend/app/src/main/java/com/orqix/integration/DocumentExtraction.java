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
    /** Blueprint PDFs (docs/build/S91): larger documents, extracted page by page, never rendered or executed. */
    public static final int BLUEPRINT_MAX_FILE_BYTES = 25 * 1024 * 1024;
    public static final int BLUEPRINT_MAX_PAGES = 60;
    public static final int BLUEPRINT_MAX_TEXT_BYTES = 1024 * 1024;
    private static final Semaphore SLOTS = new Semaphore(2);

    /**
     * Text of each page of a PDF, in page order. {@code javascript} and {@code embeddedFiles} report document-level
     * active content that was present and ignored (best-effort detection; nothing is ever executed or extracted from it).
     */
    public record PdfPages(List<String> pages, boolean javascript, boolean embeddedFiles) {
        public PdfPages { pages = List.copyOf(pages); }
    }

    public String extract(byte[] bytes, String mime, long deadline) {
        if (bytes == null || bytes.length == 0 || bytes.length > MAX_FILE_BYTES) { throw failure("DOCUMENT_CONTENT_TOO_LARGE"); }
        return run(DocumentExtractionWorker.type(mime), bytes, deadline, MAX_TEXT_BYTES, "-Xmx128m");
    }

    /**
     * Page-by-page text of a blueprint PDF in the same isolated worker JVM (bounded heap, output, concurrency and time).
     * Failure codes: DOCUMENT_CONTENT_TOO_LARGE, DOCUMENT_EXTRACTION_LIMIT_EXCEEDED (pages or text), DOCUMENT_ENCRYPTED,
     * DOCUMENT_EXTRACTION_INVALID (not a readable PDF), DOCUMENT_EXTRACTION_TIMEOUT, DOCUMENT_EXTRACTION_BUSY.
     */
    public PdfPages extractPdfPages(byte[] bytes, long deadline) {
        if (bytes == null || bytes.length == 0 || bytes.length > BLUEPRINT_MAX_FILE_BYTES) { throw failure("DOCUMENT_CONTENT_TOO_LARGE"); }
        if (bytes.length < 5 || !new String(bytes, 0, 5, StandardCharsets.US_ASCII).equals("%PDF-")) { throw failure("DOCUMENT_EXTRACTION_INVALID"); }
        String output = run(DocumentExtractionWorker.PDF_PAGES, bytes, deadline, BLUEPRINT_MAX_TEXT_BYTES + 4096, "-Xmx256m");
        int header = output.indexOf('\n');
        if (header < 0 || !output.startsWith(DocumentExtractionWorker.PAGES_HEADER)) { throw failure("DOCUMENT_EXTRACTION_INVALID"); }
        String[] flags = output.substring(DocumentExtractionWorker.PAGES_HEADER.length(), header).trim().split(" ");
        int count;
        try { count = Integer.parseInt(flags[0]); } catch (RuntimeException e) { throw failure("DOCUMENT_EXTRACTION_INVALID"); }
        List<String> pages = new ArrayList<>(List.of(output.substring(header + 1).split("\f", -1)));
        if (!pages.isEmpty() && pages.getLast().isEmpty()) { pages.removeLast(); }
        if (pages.size() != count || count < 1 || count > BLUEPRINT_MAX_PAGES) { throw failure("DOCUMENT_EXTRACTION_INVALID"); }
        return new PdfPages(pages, flags.length > 1 && flags[1].equals("javascript=true"), flags.length > 2 && flags[2].equals("embedded=true"));
    }

    private String run(String type, byte[] bytes, long deadline, int maxOutput, String heap) {
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
            List<String> command = workerCommand(type, source, output, heap);
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
            if (!Files.isRegularFile(output) || Files.size(output) > maxOutput) { throw failure("DOCUMENT_EXTRACTION_LIMIT_EXCEEDED"); }
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
        return workerCommand(type, source, output, "-Xmx128m");
    }

    static List<String> workerCommand(String type, Path source, Path output, String heap) {
        String executable = Path.of(System.getProperty("java.home"), "bin", System.getProperty("os.name")
                .toLowerCase().contains("windows") ? "java.exe" : "java").toString();
        String classpath = System.getProperty("java.class.path");
        List<String> command = new ArrayList<>(List.of(executable, heap, "-XX:MaxMetaspaceSize=64m",
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
