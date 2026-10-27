package com.orqix.integration;

import java.util.List;

import com.orqix.shared.problem.ApiException;
import com.orqix.shared.problem.ProblemCode;

/**
 * Secret references are file names in one deployment-wide directory (S6). Outbound webhook signing secrets live in
 * their own name space, {@code webhook-...} (docs/build/S99 B4), so a person who may configure one workspace's webhooks
 * cannot make Orqix sign with a secret that verifies inbound events or authenticates connections, adapters or brokers,
 * and no other configuration can borrow a webhook secret.
 */
public final class SecretNamespaces {

    public static final String WEBHOOK = "webhook-";

    private SecretNamespaces() {
    }

    /** True for a reference reserved for webhook signing or Kafka credentials. */
    public static boolean reserved(String secretRef) {
        return secretRef != null && secretRef.startsWith(WEBHOOK);
    }

    /** 422 when a connection, adapter or inbound trigger names a reserved reference. */
    public static void refuseReserved(String secretRef, String pointer) {
        if (reserved(secretRef)) {
            throw new ApiException(ProblemCode.VALIDATION_FAILED, "References starting with webhook- are reserved.")
                    .withFieldErrors(List.of(new ApiException.FieldError(pointer, "reserved",
                            "References starting with webhook- are reserved for outbound webhook signing secrets.")));
        }
    }
}
