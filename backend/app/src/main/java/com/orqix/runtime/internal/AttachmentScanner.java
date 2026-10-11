package com.orqix.runtime.internal;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Malware scanner port (docs/build/S17 section 3). The default implementation does not scan; production deployments
 * that accept files from outside must enable a real scanner (the ClamAV implementation, or their own bean of this type).
 * Implementations must be thread-safe and must not log file content.
 */
public interface AttachmentScanner {

    /** Result of one scan. {@code ERROR} means the scanner could not decide: the file is stored but never served. */
    enum Status {
        NOT_SCANNED("notScanned"), CLEAN("clean"), INFECTED("infected"), ERROR("error");

        private final String value;

        Status(String value) {
            this.value = value;
        }

        /** The API and database value. */
        public String value() {
            return value;
        }

        public static Status of(String value) {
            for (Status s : values()) {
                if (s.value.equals(value)) {
                    return s;
                }
            }
            throw new IllegalArgumentException("Unknown scan status " + value);
        }

        /** Files in these states are never served. */
        public boolean blocksDownload() {
            return this == INFECTED || this == ERROR;
        }
    }

    Status scan(byte[] content);

    /**
     * Default scanner: scans nothing and says so ({@code notScanned}). Replaced by {@link ClamAvScanner} when
     * {@code orqix.attachments.clamav.enabled=true}; a deployment's own scanner bean is marked {@code @Primary}.
     */
    @Configuration
    class Defaults {

        @Bean
        @ConditionalOnProperty(name = "orqix.attachments.clamav.enabled", havingValue = "false", matchIfMissing = true)
        AttachmentScanner notScannedAttachmentScanner() {
            return content -> Status.NOT_SCANNED;
        }
    }
}
