package com.orqix.integration;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Text extraction port (DOC08, docs/build/S52). A deployment that wants real optical character recognition supplies
 * its own bean of this type; the product ships only {@link LocalStub}, which reads plain text and CSV and states that
 * every other type is unsupported. Implementations must be deterministic for the same bytes, must not log file
 * content and must not call a service the deployment has not approved.
 */
public interface OcrProvider {

    /** Largest text stored for one file. */
    int MAX_TEXT_CHARS = 100_000;

    /** {@code text} is non-null exactly when {@code supported}. */
    record Result(boolean supported, String text) {
        public static Result unsupported() {
            return new Result(false, null);
        }

        public static Result of(String text) {
            return new Result(true, text);
        }
    }

    /** Stable provider name recorded with each result (at most 64 characters). */
    String id();

    Result extract(byte[] content, String contentType);

    /**
     * Layout analysis for document extraction (CG-A06, docs/build/S146-parts/B-document-extraction.md): pages, lines
     * and words with boxes and confidences, and the service's own key-value pairs where it has them. The default reads
     * the plain text of {@link #extract} without geometry, so every existing provider keeps working.
     *
     * @param documentType the skill's document type ({@code invoice}, {@code idDocument}, {@code bankStatement},
     *                     {@code custom}): cloud services choose their prebuilt model from it
     * @throws OcrException when the file cannot be read (unsupported type, too large, service failure)
     */
    default OcrDocument analyze(byte[] content, String contentType, String documentType, java.time.Duration timeout) {
        Result result = extract(content, contentType);
        if (!result.supported()) {
            throw new OcrException(OcrException.Kind.UNSUPPORTED, 0, id() + ": unsupported file type");
        }
        return OcrDocument.plainText(id(), result.text());
    }

    @Configuration
    class Defaults {

        @Bean
        @ConditionalOnMissingBean(OcrProvider.class)
        OcrProvider localStubOcrProvider() {
            return new LocalStub();
        }
    }

    /** Local stub: no OCR engine. Text and CSV are returned as stored; everything else is unsupported. */
    final class LocalStub implements OcrProvider {

        @Override
        public String id() {
            return "local-stub";
        }

        @Override
        public Result extract(byte[] content, String contentType) {
            if (!"text/plain".equals(contentType) && !"text/csv".equals(contentType)) {
                return Result.unsupported();
            }
            String text = new String(content, java.nio.charset.StandardCharsets.UTF_8);
            return Result.of(text.length() > MAX_TEXT_CHARS ? text.substring(0, MAX_TEXT_CHARS) : text);
        }
    }
}
