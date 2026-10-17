package com.orqix.runtime.spike;

import java.util.concurrent.atomic.AtomicInteger;

import org.flowable.engine.delegate.DelegateExecution;
import org.flowable.engine.delegate.JavaDelegate;

import com.orqix.runtime.internal.JobFailure;

/** Spike S-4 delegate: counts invocations and fails according to {@link #mode}. */
public class CountingDelegate implements JavaDelegate {

    public enum Mode {
        FAIL_RETRYABLE, FAIL_NON_RETRYABLE, FAIL_UNEXPECTED, SUCCEED
    }

    public static final AtomicInteger INVOCATIONS = new AtomicInteger();
    public static volatile Mode mode = Mode.FAIL_RETRYABLE;

    @Override
    public void execute(DelegateExecution execution) {
        INVOCATIONS.incrementAndGet();
        switch (mode) {
            case FAIL_RETRYABLE -> throw new JobFailure(true, "RETRIES_EXHAUSTED", "HTTP_500", "retryable", 500,
                    INVOCATIONS.get(), null, "spike");
            case FAIL_NON_RETRYABLE -> throw new JobFailure(false, "EFFECT_REJECTED", "HTTP_400", "permanent", 400,
                    INVOCATIONS.get(), null, "spike");
            case FAIL_UNEXPECTED -> throw new IllegalStateException();
            case SUCCEED -> execution.setVariable("spikeDone", true);
        }
    }
}
