package com.orqix.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.orqix.runtime.internal.RunReferences;
import com.orqix.support.RuntimeTestBase;

/**
 * S103: with {@code orqix.runs.reference-allocation=fast} the request number is taken in its own short transaction.
 * Concurrent starts of several workflows still get unique, increasing references, and the workspace counter is not held
 * while a start is still running (another start can take the next number meanwhile).
 */
@TestPropertySource(properties = "orqix.runs.reference-allocation=fast")
class RunReferencesFastModeTest extends RuntimeTestBase {

    private long number(String reference) {
        return Long.parseLong(reference.substring("ORQ-".length()));
    }

    @Test
    void concurrentStartsOfSeveralWorkflowsGetUniqueReferences() throws Exception {
        UUID first = publishPurchase(PURCHASING, null);
        UUID second = publishPurchase(PURCHASING, null);
        List<String> users = List.of("rhea.requester", "mani.manager", "max.manager");
        // More starts at once than the application pool has connections (15): each start already holds one, so taking the
        // number must not need a second connection from that same pool.
        ExecutorService pool = Executors.newFixedThreadPool(24);
        try {
            List<Future<Response>> starts = new ArrayList<>();
            for (int i = 0; i < 24; i++) {
                UUID workflow = i % 2 == 0 ? first : second;
                String user = users.get(i % users.size());
                starts.add(pool.submit(() -> api(user).post(Map.of("workflowId", workflow, "input", input(BigDecimal.ONE)),
                        UUID.randomUUID().toString(), "/workspaces/{ws}/runs", PURCHASING)));
            }
            List<Long> numbers = new ArrayList<>();
            for (Future<Response> f : starts) {
                Response r = f.get();
                assertThat(r.status()).as(r.toString()).isEqualTo(201);
                numbers.add(number(r.body().path("reference").asString()));
            }
            assertThat(numbers).doesNotHaveDuplicates();
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void theNumberIsCommittedOnItsOwnSoARolledBackStartLeavesAGapAndHoldsNothing() {
        long before = seq();
        Long taken = transactions.execute(status -> {
            long n = references.next(PURCHASING);
            status.setRollbackOnly(); // the start fails after taking its number
            return n;
        });
        assertThat(taken).isEqualTo(before + 1);
        // The number stays used (a gap), and the counter row is free again: another transaction takes the next one.
        assertThat(seq()).isEqualTo(before + 1);
        assertThat(references.next(PURCHASING)).isEqualTo(before + 2);
    }

    private long seq() {
        return jdbc.sql("SELECT run_seq FROM workspace WHERE id = :ws").param("ws", PURCHASING).query(Long.class).single();
    }

    @Autowired
    private RunReferences references;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private TransactionTemplate transactions;

    @BeforeEach
    void transactions() {
        transactions = new TransactionTemplate(transactionManager);
    }
}
