package com.orqix.runtime.spike;

import java.util.UUID;

import org.flowable.common.engine.api.delegate.event.FlowableEvent;
import org.flowable.common.engine.api.delegate.event.FlowableEventListener;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Spike S-10: writes an orqix row from inside the engine command (TASK_CREATED). */
class SpikeAuditListener implements FlowableEventListener {

    private final JdbcClient jdbc;
    private final String marker;

    SpikeAuditListener(JdbcClient jdbc, String marker) {
        this.jdbc = jdbc;
        this.marker = marker;
    }

    @Override
    public void onEvent(FlowableEvent event) {
        jdbc.sql("""
                INSERT INTO audit_event (id, actor_type, action, resource_type, resource_id, outcome, correlation_id)
                VALUES (:id, 'system', 'spike.listener', 'spike', :marker, 'success', 'spike')
                """)
                .param("id", UUID.randomUUID()).param("marker", marker).update();
    }

    @Override
    public boolean isFailOnException() {
        return true;
    }

    @Override
    public boolean isFireOnTransactionLifecycleEvent() {
        return false;
    }

    @Override
    public String getOnTransaction() {
        return null;
    }
}
