package com.orqix.shared.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Attachment limits (docs/build/S17 section 2). The per-file maximum cannot exceed 10 MiB: the database and the workflow
 * schema ({@code maxFileBytes}) are sized for it, and RequestLimitsFilter caps attachment requests at it (the container
 * multipart cap is larger for blueprint PDFs), so a larger value fails
 * at startup instead of at the first upload.
 *
 * @param maxFileBytes        largest file
 * @param maxFilesPerRun      files that can be attached to one request (removed files do not count)
 * @param maxBytesPerRun      total size of the files of one request
 * @param stagedTtl           how long a file chosen on the request form is kept before the request is started
 * @param maxStagedPerSubject files a person can have waiting on request forms at one time
 * @param uploadsPerMinute    uploads per person per minute (database-backed, so it holds across pods)
 */
@ConfigurationProperties("orqix.attachments")
public record AttachmentSettings(@DefaultValue("10485760") long maxFileBytes, @DefaultValue("25") int maxFilesPerRun,
        @DefaultValue("52428800") long maxBytesPerRun, @DefaultValue("24h") Duration stagedTtl,
        @DefaultValue("10") int maxStagedPerSubject, @DefaultValue("30") int uploadsPerMinute,
        @DefaultValue ClamAv clamav) {

    /** Platform maximum for one file. */
    public static final long PLATFORM_MAX_FILE_BYTES = 10L * 1024 * 1024;

    /**
     * Optional ClamAV scanner over the clamd TCP protocol. Disabled by default: without it files are stored with the
     * scan status {@code notScanned}.
     */
    public record ClamAv(@DefaultValue("false") boolean enabled, @DefaultValue("localhost") String host,
            @DefaultValue("3310") int port, @DefaultValue("10s") Duration timeout) {
        public ClamAv {
            if (host == null || host.isBlank() || host.length() > 253 || port < 1 || port > 65535
                    || timeout == null || timeout.compareTo(Duration.ofMillis(100)) < 0
                    || timeout.compareTo(Duration.ofSeconds(60)) > 0) {
                throw new IllegalArgumentException("ClamAV requires a host, a valid port and a timeout of 100 milliseconds to 60 seconds.");
            }
        }
    }

    public AttachmentSettings {
        if (maxFileBytes < 1 || maxFileBytes > PLATFORM_MAX_FILE_BYTES) {
            throw new IllegalArgumentException("orqix.attachments.max-file-bytes must be between 1 and 10 MiB.");
        }
        if (maxFilesPerRun < 1 || maxFilesPerRun > 200) {
            throw new IllegalArgumentException("orqix.attachments.max-files-per-run must be between 1 and 200.");
        }
        if (maxBytesPerRun < maxFileBytes || maxBytesPerRun > 1024L * 1024 * 1024) {
            throw new IllegalArgumentException(
                    "orqix.attachments.max-bytes-per-run must be between the file limit and 1 GiB.");
        }
        if (stagedTtl == null || stagedTtl.compareTo(Duration.ofMinutes(5)) < 0
                || stagedTtl.compareTo(Duration.ofDays(7)) > 0) {
            throw new IllegalArgumentException("orqix.attachments.staged-ttl must be between 5 minutes and 7 days.");
        }
        if (maxStagedPerSubject < 1 || maxStagedPerSubject > 100) {
            throw new IllegalArgumentException("orqix.attachments.max-staged-per-subject must be between 1 and 100.");
        }
        if (uploadsPerMinute < 1 || uploadsPerMinute > 1000) {
            throw new IllegalArgumentException("orqix.attachments.uploads-per-minute must be between 1 and 1000.");
        }
        if (clamav == null) {
            clamav = new ClamAv(false, "localhost", 3310, Duration.ofSeconds(10));
        }
    }
}
