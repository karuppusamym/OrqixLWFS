package com.orqix.runtime.internal;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Set;
import java.util.UUID;

import javax.sql.DataSource;

import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

/**
 * Hands out the number of a request reference (ORQ-n) per workspace (docs/build/S103).
 *
 * <ul>
 * <li>{@code gapless} (default): the number is taken in the start transaction itself, so the workspace counter row stays
 * locked until that start commits. Numbers have no gaps, but starts in one workspace queue behind each other for the
 * whole start, engine work included (S92 F1).</li>
 * <li>{@code fast}: the number is taken by one auto-committed statement on a small pool of its own, so the counter is
 * held for milliseconds. Numbers stay unique and increasing; a start that fails after taking its number leaves a gap.
 * The pool is separate from the application pool on purpose: a start already holds an application connection, and
 * asking that same pool for a second one would starve it when as many starts as connections run at once.</li>
 * </ul>
 * The setting is {@code orqix.runs.reference-allocation}. Every replica of a deployment must use the same value.
 */
@Component
public class RunReferences implements DisposableBean {

    private static final String NEXT = "UPDATE workspace SET run_seq = run_seq + 1 WHERE id = :ws RETURNING run_seq";
    private static final String NEXT_JDBC = "UPDATE workspace SET run_seq = run_seq + 1 WHERE id = ? RETURNING run_seq";

    private final JdbcClient jdbc;
    private final HikariDataSource own;

    public RunReferences(JdbcClient jdbc, DataSource dataSource,
            @Value("${orqix.runs.reference-allocation:gapless}") String mode,
            @Value("${orqix.runs.reference-pool-size:4}") int poolSize) {
        if (!Set.of("gapless", "fast").contains(mode)) {
            throw new IllegalArgumentException("orqix.runs.reference-allocation must be gapless or fast");
        }
        if (poolSize < 1 || poolSize > 32) {
            throw new IllegalArgumentException("orqix.runs.reference-pool-size must be between 1 and 32");
        }
        this.jdbc = jdbc;
        this.own = "fast".equals(mode) ? ownPool(dataSource, poolSize) : null;
    }

    /** A small pool with the application pool's settings (URL, user, session settings such as lock_timeout). */
    private static HikariDataSource ownPool(DataSource dataSource, int poolSize) {
        HikariDataSource main;
        try {
            main = dataSource.unwrap(HikariDataSource.class);
        } catch (SQLException e) {
            throw new IllegalStateException("orqix.runs.reference-allocation=fast needs the Hikari connection pool", e);
        }
        HikariConfig config = new HikariConfig();
        main.copyStateTo(config);
        config.setPoolName(main.getPoolName() + "-references");
        config.setMaximumPoolSize(poolSize);
        config.setMinimumIdle(1);
        config.setAutoCommit(true);
        return new HikariDataSource(config);
    }

    public boolean fast() {
        return own != null;
    }

    /** The next number of the workspace. In fast mode it is already committed when this returns. */
    public long next(UUID workspaceId) {
        if (own == null) {
            return jdbc.sql(NEXT).param("ws", workspaceId).query(Long.class).single();
        }
        try (Connection c = own.getConnection(); PreparedStatement statement = c.prepareStatement(NEXT_JDBC)) {
            statement.setObject(1, workspaceId);
            try (ResultSet rs = statement.executeQuery()) {
                if (!rs.next()) {
                    throw new IllegalStateException("Workspace " + workspaceId + " has no request counter");
                }
                return rs.getLong(1);
            }
        } catch (SQLException e) {
            throw new org.springframework.dao.DataAccessResourceFailureException("Could not take a request number", e);
        }
    }

    @Override
    public void destroy() {
        if (own != null) {
            own.close();
        }
    }
}
