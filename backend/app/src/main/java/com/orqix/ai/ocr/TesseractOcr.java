package com.orqix.ai.ocr;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

import com.orqix.integration.OcrDocument;
import com.orqix.integration.OcrException;

/**
 * The local Tesseract engine for images (private deployments): the configured binary runs in a bounded child process
 * ({@code tesseract <image> <base> -l <languages> tsv}) with a deadline after which it is killed, at most two at a
 * time, in a temporary directory that is always removed. Output is the TSV layout (pages, lines and words with boxes
 * and confidences), parsed strictly by {@link #parseTsv}.
 */
final class TesseractOcr {

    static final int MAX_TSV_BYTES = 8 * 1024 * 1024;
    private static final Semaphore SLOTS = new Semaphore(2);

    private final OcrProperties.Tesseract config;

    TesseractOcr(OcrProperties.Tesseract config) {
        this.config = config;
    }

    boolean enabled() {
        return config.enabled();
    }

    /** The command for one image (visible for tests). */
    List<String> command(Path image, Path outputBase) {
        return List.of(config.path(), image.toString(), outputBase.toString(), "-l", config.languages(), "tsv");
    }

    OcrDocument read(String provider, byte[] image, Duration timeout) {
        if (!config.enabled()) {
            throw new OcrException(OcrException.Kind.UNSUPPORTED, 0, provider + ": no local OCR engine");
        }
        if (image.length > config.maxBytes()) {
            throw new OcrException(OcrException.Kind.TOO_LARGE, 0, provider + ": image too large");
        }
        Duration budget = timeout.compareTo(config.timeout()) < 0 ? timeout : config.timeout();
        long deadline = System.nanoTime() + budget.toNanos();
        Path directory = null;
        Process process = null;
        boolean acquired = false;
        try {
            acquired = SLOTS.tryAcquire(Math.min(budget.toMillis(), 1000), TimeUnit.MILLISECONDS);
            if (!acquired) {
                throw new OcrException(OcrException.Kind.UNAVAILABLE, 0, provider + ": local OCR busy");
            }
            directory = Files.createTempDirectory("orqix-ocr-");
            Path source = directory.resolve("page.img");
            Path base = directory.resolve("out");
            Files.write(source, image);
            process = new ProcessBuilder(command(source, base)).directory(directory.toFile())
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0 || !process.waitFor(remaining, TimeUnit.NANOSECONDS)) {
                process.destroyForcibly();
                process.waitFor(2, TimeUnit.SECONDS);
                throw new OcrException(OcrException.Kind.UNAVAILABLE, 0, provider + ": local OCR timeout");
            }
            if (process.exitValue() != 0) {
                throw new OcrException(OcrException.Kind.REJECTED, 0, provider + ": local OCR exit "
                        + process.exitValue());
            }
            Path tsv = directory.resolve("out.tsv");
            if (!Files.isRegularFile(tsv) || Files.size(tsv) > MAX_TSV_BYTES) {
                throw new OcrException(OcrException.Kind.UNAVAILABLE, 0, provider + ": local OCR output missing");
            }
            return parseTsv(provider, Files.readString(tsv, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new OcrException(OcrException.Kind.UNAVAILABLE, 0, provider + ": " + e.getClass().getSimpleName());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new OcrException(OcrException.Kind.UNAVAILABLE, 0, provider + ": interrupted");
        } finally {
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
            }
            if (directory != null) {
                try (var files = Files.list(directory)) {
                    for (Path p : files.toList()) {
                        Files.deleteIfExists(p);
                    }
                } catch (IOException ignored) {
                    // best effort; the directory is private to this call
                }
                try {
                    Files.deleteIfExists(directory);
                } catch (IOException ignored) {
                    // best effort
                }
            }
            if (acquired) {
                SLOTS.release();
            }
        }
    }

    /**
     * Parses Tesseract's TSV output: header {@code level page_num block_num par_num line_num word_num left top width
     * height conf text}; level 1 rows give the page size, level 5 rows the words (confidence 0-100, -1 for layout rows),
     * grouped into lines by page, block, paragraph and line number. A malformed row means the output is not trusted.
     */
    static OcrDocument parseTsv(String provider, String tsv) {
        String[] rows = tsv.split("\\R");
        if (rows.length == 0 || !rows[0].startsWith("level\tpage_num\tblock_num\tpar_num\tline_num\tword_num\tleft\ttop"
                + "\twidth\theight\tconf\ttext")) {
            throw new OcrException(OcrException.Kind.UNAVAILABLE, 0, provider + ": unexpected local OCR output");
        }
        Map<Integer, int[]> sizes = new LinkedHashMap<>();
        Map<Integer, Map<String, List<String[]>>> lines = new LinkedHashMap<>();
        int words = 0;
        for (int i = 1; i < rows.length; i++) {
            if (rows[i].isEmpty()) {
                continue;
            }
            String[] f = rows[i].split("\t", -1);
            if (f.length < 11 || f.length > 12) {
                throw new OcrException(OcrException.Kind.UNAVAILABLE, 0, provider + ": malformed local OCR row");
            }
            int level;
            int page;
            try {
                level = Integer.parseInt(f[0]);
                page = Integer.parseInt(f[1]);
                for (int c = 2; c <= 9; c++) {
                    Integer.parseInt(f[c]);
                }
                Double.parseDouble(f[10]);
            } catch (NumberFormatException e) {
                throw new OcrException(OcrException.Kind.UNAVAILABLE, 0, provider + ": malformed local OCR row");
            }
            if (page < 1 || page > OcrDocument.MAX_PAGES) {
                continue;
            }
            if (level == 1) {
                sizes.put(page, new int[] {Integer.parseInt(f[8]), Integer.parseInt(f[9])});
            } else if (level == 5) {
                String text = f.length == 12 ? f[11].strip() : "";
                if (text.isEmpty() || words++ > OcrDocument.MAX_LINES * 20) {
                    continue;
                }
                String key = f[2] + "/" + f[3] + "/" + f[4];
                lines.computeIfAbsent(page, k -> new LinkedHashMap<>()).computeIfAbsent(key, k -> new ArrayList<>())
                        .add(f);
            }
        }
        List<OcrDocument.Page> pages = new ArrayList<>();
        int count = 0;
        for (var p : lines.entrySet()) {
            int[] size = sizes.getOrDefault(p.getKey(), new int[] {0, 0});
            double width = size[0];
            double height = size[1];
            List<OcrDocument.Line> out = new ArrayList<>();
            for (List<String[]> line : p.getValue().values()) {
                if (count++ >= OcrDocument.MAX_LINES) {
                    break;
                }
                List<OcrDocument.Word> ws = new ArrayList<>();
                List<OcrDocument.Box> boxes = new ArrayList<>();
                StringBuilder text = new StringBuilder();
                double sum = 0;
                int scored = 0;
                for (String[] w : line) {
                    OcrDocument.Box box = width > 0 && height > 0 ? OcrDocument.Box.of(Integer.parseInt(w[6]) / width,
                            Integer.parseInt(w[7]) / height, Integer.parseInt(w[8]) / width,
                            Integer.parseInt(w[9]) / height) : null;
                    double conf = Double.parseDouble(w[10]);
                    Double c = conf < 0 ? null : Math.min(1, conf / 100);
                    if (c != null) {
                        sum += c;
                        scored++;
                    }
                    ws.add(new OcrDocument.Word(w[11].strip(), box, c));
                    boxes.add(box);
                    if (!text.isEmpty()) {
                        text.append(' ');
                    }
                    text.append(w[11].strip());
                }
                out.add(new OcrDocument.Line(text.toString(), OcrDocument.Box.union(boxes),
                        scored == 0 ? null : sum / scored, ws));
            }
            pages.add(new OcrDocument.Page(p.getKey(), width, height, "pixel", out));
        }
        for (var s : sizes.entrySet()) {
            if (!lines.containsKey(s.getKey())) {
                pages.add(new OcrDocument.Page(s.getKey(), s.getValue()[0], s.getValue()[1], "pixel", List.of()));
            }
        }
        pages.sort(java.util.Comparator.comparingInt(OcrDocument.Page::number));
        return new OcrDocument(provider, "tesseract", pages, List.of(), List.of());
    }
}
