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
