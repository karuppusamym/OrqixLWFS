package com.orqix.operations.retention;

import java.net.URI;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Platform settings of run retention (CG-O07, docs/build/S144) under {@code orqix.retention}. The archive store is
 * chosen here: {@code filesystem} (default) or {@code s3}.
 */
@Configuration
public class RetentionSettings {

    private final int maxUnitsPerSweep;
    private final long maxArchiveBytes;
    private final int historyBatch;

    public RetentionSettings(@Value("${orqix.retention.max-requests-per-sweep:200}") int maxUnitsPerSweep,
            @Value("${orqix.retention.max-archive-bytes:268435456}") long maxArchiveBytes,
            @Value("${orqix.retention.history-batch:100}") int historyBatch) {
        this.maxUnitsPerSweep = Math.max(1, Math.min(maxUnitsPerSweep, 10_000));
        this.maxArchiveBytes = maxArchiveBytes;
        this.historyBatch = Math.max(1, Math.min(historyBatch, 1_000));
    }

    public int maxUnitsPerSweep() {
        return maxUnitsPerSweep;
    }

    public long maxArchiveBytes() {
        return maxArchiveBytes;
    }

    public int historyBatch() {
        return historyBatch;
    }

    @Bean
    ArchiveStore runArchiveStore(@Value("${orqix.retention.store:filesystem}") String store,
            @Value("${orqix.retention.filesystem.root:data/run-archives}") String root,
            @Value("${orqix.retention.s3.endpoint:}") String endpoint,
            @Value("${orqix.retention.s3.region:}") String region,
            @Value("${orqix.retention.s3.bucket:}") String bucket,
            @Value("${orqix.retention.s3.prefix:}") String prefix,
            @Value("${orqix.retention.s3.access-key-id:}") String accessKeyId,
            @Value("${orqix.retention.s3.secret-access-key:}") String secretAccessKey,
            @Value("${orqix.retention.s3.timeout:10s}") Duration timeout, Clock clock) {
        return switch (store) {
            case "filesystem" -> new FileSystemArchiveStore(Path.of(root));
            case "s3" -> new S3ArchiveStore(endpoint.isBlank() ? null : URI.create(endpoint), region, bucket, prefix,
                    accessKeyId, secretAccessKey, timeout, clock);
            default -> throw new IllegalStateException("orqix.retention.store must be filesystem or s3");
        };
    }
}
