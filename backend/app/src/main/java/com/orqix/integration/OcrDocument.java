package com.orqix.integration;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * The layout an OCR provider read from one file (CG-A06, docs/build/S146-parts/B-document-extraction.md): pages with
 * their size, lines (and words where the service returns them) with a bounding box and a confidence, key-value pairs
 * where the service extracts them itself (mapped to template field names when the adapter knows the service's field)
 * and the line-item amounts of an invoice. Boxes are normalized to the page (0..1 from the top left), so the
 * verification screen can draw them on any rendering of the page. Confidences are 0..1 or null when not reported.
 */
public record OcrDocument(String provider, String model, List<Page> pages, List<KeyValue> keyValues,
        List<BigDecimal> lineItemAmounts) {

    /** Largest number of lines kept for one document (bounds prompts and stored layouts). */
    public static final int MAX_LINES = 5_000;
    /** Largest number of pages read from one document. */
    public static final int MAX_PAGES = 60;

    public OcrDocument {
        pages = List.copyOf(pages);
        keyValues = List.copyOf(keyValues);
        lineItemAmounts = List.copyOf(lineItemAmounts);
    }

    /** @param unit {@code pixel}, {@code inch} or {@code point}; width and height may be 0 when unknown */
    public record Page(int number, double width, double height, String unit, List<Line> lines) {
        public Page {
            lines = List.copyOf(lines);
        }
    }

    /** One line of text; {@code box} may be null (plain text has no geometry). */
    public record Line(String text, Box box, Double confidence, List<Word> words) {
        public Line {
            words = words == null ? List.of() : List.copyOf(words);
        }
    }

    public record Word(String text, Box box, Double confidence) {
    }

    /** A rectangle normalized to the page: left, top, width, height, each 0..1. */
    public record Box(double x, double y, double w, double h) {

        public static Box of(double x, double y, double w, double h) {
            return new Box(clamp(x), clamp(y), clamp(w), clamp(h));
        }

        /** The smallest box around the given boxes (nulls ignored); null when there is none. */
        public static Box union(List<Box> boxes) {
            double x1 = 1;
            double y1 = 1;
            double x2 = 0;
            double y2 = 0;
            boolean any = false;
            for (Box b : boxes) {
                if (b == null) {
                    continue;
                }
                any = true;
                x1 = Math.min(x1, b.x());
                y1 = Math.min(y1, b.y());
                x2 = Math.max(x2, b.x() + b.w());
                y2 = Math.max(y2, b.y() + b.h());
            }
            return any ? of(x1, y1, x2 - x1, y2 - y1) : null;
        }

        private static double clamp(double v) {
            return Double.isFinite(v) ? Math.max(0, Math.min(1, Math.round(v * 10000) / 10000.0)) : 0;
        }
    }

    /**
     * A key-value pair the service extracted itself. {@code field} is the template field it maps to, or null.
     */
    public record KeyValue(String field, String key, String value, Double confidence, Integer page, Box box) {
    }

    /** A line located in the document text: page (1-based), index on the page, and its character span. */
    public record LineRef(int page, int index, Line line, int start, int end) {
    }

    /** Every line in reading order with its span in {@link #text()} (lines joined with a newline). */
    public List<LineRef> lines() {
        List<LineRef> refs = new ArrayList<>();
        int offset = 0;
        for (Page p : pages) {
            int i = 0;
            for (Line l : p.lines()) {
                refs.add(new LineRef(p.number(), i++, l, offset, offset + l.text().length()));
                offset += l.text().length() + 1;
            }
        }
        return refs;
    }

    /** The recognized text: every line, joined with a newline, in page order. */
    public String text() {
        StringBuilder s = new StringBuilder();
        for (Page p : pages) {
            for (Line l : p.lines()) {
                if (!s.isEmpty()) {
                    s.append('\n');
                }
                s.append(l.text());
            }
        }
        return s.toString();
    }

    /** A document of plain text lines without geometry (text files, the local stub). */
    public static OcrDocument plainText(String provider, String text) {
        List<Line> lines = new ArrayList<>();
        for (String line : text.split("\\R", -1)) {
            if (lines.size() >= MAX_LINES) {
                break;
            }
            lines.add(new Line(line, null, null, List.of()));
        }
        while (!lines.isEmpty() && lines.getLast().text().isBlank()) {
            lines.removeLast();
        }
        return new OcrDocument(provider, "text", List.of(new Page(1, 0, 0, "none", lines)), List.of(), List.of());
    }
}
