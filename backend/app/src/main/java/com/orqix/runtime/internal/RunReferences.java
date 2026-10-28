package com.orqix.runtime.internal;

import java.util.Set;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Hands out the number of a request reference (ORQ-n) per workspace (docs/build/S103).
 *
 * <ul>
 * <li>{@code gapless} (default): the number is taken in the start transaction itself, so the workspace counter row stays
 * locked until that start commits. Numbers have no gaps, but starts in one workspace queue behind each other for the
 * whole start, engine work included (S92 F1).</li>
 * <li>{@code fast}: the number is taken in its own short transaction that commits at once, so the counter is held for
 * milliseconds. Numbers stay unique and increasing; a start that fails after taking its number leaves a gap.</li>
 * </ul>
 * The setting is {@code orqix.runs.reference-allocation}. Every replica of a deployment must use the same value.
 */
@Component
public class RunReferences {

    private static final String NEXT = "UPDATE workspace SET run_seq = run_seq + 1 WHERE id = :ws RETURNING run_seq";

    private final JdbcClient jdbc;
    private final TransactionTemplate separate;
    private final boolean fast;

    public RunReferences(JdbcClient jdbc, PlatformTransactionManager transactions,
            @Value("${orqix.runs.reference-allocation:gapless}") String mode) {
        if (!Set.of("gapless", "fast").contains(mode)) {
            throw new IllegalArgumentException("orqix.runs.reference-allocation must be gapless or fast");
        }
        this.jdbc = jdbc;
        this.fast = "fast".equals(mode);
        this.separate = new TransactionTemplate(transactions);
        this.separate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public boolean fast() {
        return fast;
    }

    /** The next number of the workspace. In fast mode it is already committed when this returns. */
    public long next(UUID workspaceId) {
        if (!fast) {
            return jdbc.sql(NEXT).param("ws", workspaceId).query(Long.class).single();
        }
        Long value = separate.execute(status -> jdbc.sql(NEXT).param("ws", workspaceId).query(Long.class).single());
        return value == null ? 0 : value;
    }
}
