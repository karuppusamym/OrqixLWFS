package com.orqix.resilience;

import java.util.List;
import java.util.UUID;

import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.annotation.DirtiesContext.HierarchyMode;
import org.springframework.test.context.TestContextManager;
import org.springframework.test.web.servlet.MockMvc;

import com.orqix.shared.json.Json;

/**
 * One running application instance ("pod") of the resilience suite. The context is created by the Spring TestContext
 * framework from a {@link Nodes} definition, so several instances can run in one JVM against the same database, each
 * with its own lock owner, connection pool, policy cache and job executor. {@link #stop()} closes the context
 * gracefully (the in-JVM equivalent of SIGTERM: Spring shutdown callbacks run, the job executor drains).
 */
public final class Node {

    public static final UUID PURCHASING = UUID.fromString("00000000-0000-7000-8000-000000000201");

    private final Class<?> definition;
    private final String name;
    private final TestContextManager manager;
    private ApplicationContext context;

    private Node(Class<?> definition, String name) {
        this.definition = definition;
        this.name = name;
        this.manager = new TestContextManager(definition);
    }

    /** Starts (or reuses the cached context of) an instance defined in {@link Nodes}. */
    public static Node start(Class<? extends Nodes.Base> definition) {
        String name = definition.getSimpleName();
        Node node = new Node(definition, name);
        node.context = node.manager.getTestContext().getApplicationContext();
        return node;
    }

    public String name() {
        return name;
    }

    /** The lock owner this instance uses for jobs and effect leases. */
    public String lockOwner() {
        return context.getEnvironment().getProperty("orqix.runtime.lock-owner");
    }

    public boolean running() {
        return context != null;
    }

    public Client as(String user) {
        return new Client(context.getBean(MockMvc.class), context.getBean(Json.class), user, null);
    }

    /** Signs in with explicit IdP groups instead of the dev-realm membership of {@code user}. */
    public Client as(String user, List<String> groups) {
        return new Client(context.getBean(MockMvc.class), context.getBean(Json.class), user, groups);
    }

    public JdbcClient jdbc() {
        return context.getBean(JdbcClient.class);
    }

    public Json json() {
        return context.getBean(Json.class);
    }

    public <T> T bean(Class<T> type) {
        return context.getBean(type);
    }

    public boolean hasBean(Class<?> type) {
        return context.getBeanNamesForType(type).length > 0;
    }

    /** Closes the application context and evicts it from the test cache; returns the shutdown time in ms. */
    public long stop() {
        if (context == null) {
            return 0;
        }
        long started = System.nanoTime();
        manager.getTestContext().markApplicationContextDirty(HierarchyMode.CURRENT_LEVEL);
        context = null;
        return (System.nanoTime() - started) / 1_000_000;
    }

    @Override
    public String toString() {
        return definition.getSimpleName();
    }
}
