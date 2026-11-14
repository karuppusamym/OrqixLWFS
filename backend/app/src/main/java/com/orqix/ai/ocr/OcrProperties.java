package com.orqix.ai.ocr;

import java.net.URI;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * OCR providers of document extraction (CG-A06, docs/build/S146-parts/B-document-extraction.md), prefix
 * {@code orqix.ocr}. Deployment-owned. The provider {@code local} always exists: PDF text layers through the isolated
 * parser JVM, plain text and CSV as stored, images through a local Tesseract binary when {@code tesseract.path} is set.
 * Cloud providers are declared under {@code providers}; a workspace chooses one of them only where the workspace AI
 * policy's residency rule admits it (see {@link OcrProviders}).
 *
 * @param defaultProvider the provider a workspace uses until an administrator chooses one (default {@code local})
 * @param tesseract       the local Tesseract engine
 * @param providers       cloud providers by id
 */
@ConfigurationProperties(prefix = "orqix.ocr")
public record OcrProperties(String defaultProvider, Tesseract tesseract, Map<String, Provider> providers) {

    public static final String LOCAL = "local";

    public OcrProperties {
        defaultProvider = defaultProvider == null || defaultProvider.isBlank() ? LOCAL : defaultProvider.trim();
        tesseract = tesseract == null ? new Tesseract(null, null, null, null) : tesseract;
        providers = providers == null ? Map.of() : java.util.Collections.unmodifiableMap(new LinkedHashMap<>(providers));
    }

    /**
     * @param path      the {@code tesseract} executable; empty disables image OCR on the local provider
     * @param languages Tesseract language codes (default {@code eng})
     * @param timeout   per-file ceiling (default 60 s)
     * @param maxBytes  largest image passed to the engine (default 10 MiB)
     */
    public record Tesseract(String path, String languages, Duration timeout, Integer maxBytes) {
        public Tesseract {
            path = path == null ? "" : path.trim();
            languages = languages == null || !languages.matches("^[a-z_]{3,10}(\\+[a-z_]{3,10}){0,4}$") ? "eng" : languages;
            timeout = timeout == null || timeout.isNegative() || timeout.isZero() ? Duration.ofSeconds(60) : timeout;
            maxBytes = maxBytes == null || maxBytes < 1 ? 10 * 1024 * 1024 : maxBytes;
        }

        public boolean enabled() {
            return !path.isEmpty();
        }
    }

    /**
     * @param kind         {@code azureDocumentIntelligence} or {@code textract}
     * @param baseUrl      the service address (Azure: the resource endpoint; Textract: default
     *                     {@code https://textract.<region>.amazonaws.com})
     * @param secretRef    S132 secret reference: the Azure key, or the AWS keys as JSON
     *                     {@code {"accessKeyId","secretAccessKey","sessionToken"}}
     * @param region       AWS region (Textract)
     * @param apiVersion   Azure Document Intelligence {@code api-version} (default {@code 2024-11-30})
     * @param residency    where the service processes data, e.g. {@code eu}; matched against the workspace rule
     * @param label        shown in Settings
     * @param timeout      ceiling of one analysis including polling (default 120 s)
     * @param pollInterval Azure operation polling interval when the service sends no Retry-After (default 1 s)
     */
    public record Provider(String kind, URI baseUrl, String secretRef, String region, String apiVersion,
            List<String> residency, String label, Duration timeout, Duration pollInterval) {
        public Provider {
            kind = kind == null ? "" : kind.trim();
            secretRef = secretRef == null ? "" : secretRef.trim();
            region = region == null ? "" : region.trim();
            apiVersion = apiVersion == null || apiVersion.isBlank() ? "2024-11-30" : apiVersion.trim();
            residency = residency == null ? List.of()
                    : residency.stream().map(s -> s.trim().toLowerCase(Locale.ROOT)).filter(s -> !s.isEmpty()).toList();
            timeout = timeout == null || timeout.isNegative() || timeout.isZero() ? Duration.ofSeconds(120) : timeout;
            pollInterval = pollInterval == null || pollInterval.isNegative() || pollInterval.isZero()
                    ? Duration.ofSeconds(1) : pollInterval;
        }

        @Override
        public String toString() {
            return "Provider[kind=" + kind + ", baseUrl=" + baseUrl + ", residency=" + residency + "]";
        }
    }
}
