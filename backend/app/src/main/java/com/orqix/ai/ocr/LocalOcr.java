package com.orqix.ai.ocr;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import com.orqix.integration.DocumentExtraction;
import com.orqix.integration.MicrosoftGraphDocuments;
import com.orqix.integration.OcrDocument;
import com.orqix.integration.OcrException;
import com.orqix.integration.OcrProvider;

/**
 * The {@code local} OCR provider: nothing leaves the deployment. Plain text and CSV are read as stored; PDFs with a
 * text layer are read page by page in the isolated parser JVM (S91 limits: 25 MiB, 60 pages; lines without boxes);
 * PNG, JPEG and TIFF images go to the local Tesseract engine when one is configured. Scanned PDFs (no text layer) and
 * images without an engine are unsupported.
 */
final class LocalOcr implements OcrProvider {

    private final DocumentExtraction pdf;
    private final TesseractOcr tesseract;

    LocalOcr(DocumentExtraction pdf, TesseractOcr tesseract) {
        this.pdf = pdf;
        this.tesseract = tesseract;
    }

    @Override
    public String id() {
        return OcrProperties.LOCAL;
    }

    @Override
    public Result extract(byte[] content, String contentType) {
        try {
            return Result.of(cut(analyze(content, contentType, "custom", Duration.ofSeconds(60)).text()));
        } catch (OcrException e) {
            return Result.unsupported();
        }
    }

    @Override
    public OcrDocument analyze(byte[] content, String contentType, String documentType, Duration timeout) {
        String type = contentType == null ? "" : contentType;
        switch (type) {
            case "text/plain", "text/csv" -> {
                return OcrDocument.plainText(id(), cut(new String(content, StandardCharsets.UTF_8)));
            }
            case "application/pdf" -> {
                DocumentExtraction.PdfPages pages;
                try {
                    pages = pdf.extractPdfPages(content, System.nanoTime() + timeout.toNanos());
                } catch (MicrosoftGraphDocuments.Failure e) {
                    throw new OcrException(switch (e.code()) {
                        case "DOCUMENT_CONTENT_TOO_LARGE", "DOCUMENT_EXTRACTION_LIMIT_EXCEEDED" -> OcrException.Kind.TOO_LARGE;
                        case "DOCUMENT_ENCRYPTED", "DOCUMENT_EXTRACTION_INVALID", "DOCUMENT_TEXT_UNAVAILABLE" ->
                                OcrException.Kind.UNSUPPORTED;
                        default -> OcrException.Kind.UNAVAILABLE;
                    }, 0, id() + ": " + e.code());
                }
                List<OcrDocument.Page> out = new ArrayList<>();
                int count = 0;
                boolean any = false;
                for (int i = 0; i < pages.pages().size(); i++) {
                    List<OcrDocument.Line> lines = new ArrayList<>();
                    for (String line : pages.pages().get(i).split("\\R")) {
                        if (line.isBlank() || count++ >= OcrDocument.MAX_LINES) {
                            continue;
                        }
                        any = true;
                        lines.add(new OcrDocument.Line(line.strip(), null, null, List.of()));
                    }
                    out.add(new OcrDocument.Page(i + 1, 0, 0, "point", lines));
                }
                if (!any) {
                    throw new OcrException(OcrException.Kind.UNSUPPORTED, 0, id() + ": PDF without a text layer");
                }
                return new OcrDocument(id(), "pdf-text", out, List.of(), List.of());
            }
            case "image/png", "image/jpeg", "image/tiff" -> {
                return tesseract.read(id(), content, timeout);
            }
            default -> throw new OcrException(OcrException.Kind.UNSUPPORTED, 0, id() + ": unsupported file type");
        }
    }

    private static String cut(String text) {
        return text.length() > MAX_TEXT_CHARS ? text.substring(0, MAX_TEXT_CHARS) : text;
    }
}
