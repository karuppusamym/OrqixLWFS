package com.orqix.runtime.internal;

import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import com.orqix.catalog.ReleaseQueries;
import com.orqix.integration.AgentWaitTools;

import tools.jackson.databind.JsonNode;

/** The runtime's answer to "which request form does this pinned workflow release have" (CG-M02, S135). */
@Component
public class AgentWaitToolsService implements AgentWaitTools {

    private final JdbcClient jdbc;
    private final ReleaseQueries releases;

    public AgentWaitToolsService(JdbcClient jdbc, ReleaseQueries releases) {
        this.jdbc = jdbc;
        this.releases = releases;
    }

    @Override
    public JsonNode workflowInputSchema(UUID workspaceId, String workflowKey, int releaseNumber) {
        return jdbc.sql("""
                SELECT r.id FROM workflow_release r JOIN workflow w ON w.workspace_id = r.workspace_id AND w.id = r.workflow_id
                WHERE r.workspace_id = :ws AND w.key = :key AND r.release_number = :n
                """).param("ws", workspaceId).param("key", workflowKey).param("n", releaseNumber).query(UUID.class)
                .optional().flatMap(id -> releases.find(workspaceId, id)).map(ReleaseQueries.ReleaseView::inputSchema)
                .filter(JsonNode::isObject).orElse(null);
    }
}
