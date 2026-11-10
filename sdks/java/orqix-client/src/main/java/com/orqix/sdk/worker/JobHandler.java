package com.orqix.sdk.worker;

import java.util.Map;

/**
 * Does the work of one job and returns the step's declared result fields (scalars: text, numbers, true/false).
 * Throw {@link BusinessErrorException} for a business "no", {@link JobFailedException} for a technical failure;
 * any other exception is reported as a retryable failure naming only its class, never its message.
 */
@FunctionalInterface
public interface JobHandler {
    Map<String, ?> handle(Job job) throws Exception;
}
