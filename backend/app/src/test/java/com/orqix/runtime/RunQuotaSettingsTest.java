package com.orqix.runtime;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

import com.orqix.runtime.internal.RunQuota;

/** S103: the open-request limit is exact only while the margin exceeds the connections that can start at once. */
class RunQuotaSettingsTest {

    @Test
    void theMarginMustExceedThePoolSizeTimesTheReplicas() {
        assertThatCode(() -> new RunQuota(null, 64, 15, 4)).doesNotThrowAnyException();
        assertThatThrownBy(() -> new RunQuota(null, 64, 20, 4)).hasMessageContaining("orqix.runs.quota-margin");
        assertThatThrownBy(() -> new RunQuota(null, 64, 15, 5)).hasMessageContaining("expected-replicas");
        assertThatCode(() -> new RunQuota(null, 128, 15, 8)).doesNotThrowAnyException();
    }
}
