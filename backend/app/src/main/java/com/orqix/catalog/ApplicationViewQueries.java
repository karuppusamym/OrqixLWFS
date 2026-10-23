package com.orqix.catalog;

import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.orqix.shared.json.Json;
import com.orqix.shared.problem.ApiException;
import com.orqix.shared.problem.ProblemCode;
import tools.jackson.databind.JsonNode;

/** Immutable view snapshots for authorized domain services. This does not grant a caller access. */
@Service
public class ApplicationViewQueries {
    public record Pin(UUID id, int version) {}
    public record Pinned(UUID id, int version, JsonNode definition) {}
    private final JdbcClient jdbc;
    private final Json json;
    public ApplicationViewQueries(JdbcClient jdbc, Json json) { this.jdbc = jdbc; this.json = json; }

    @Transactional(readOnly = true)
    public Pinned require(UUID workspace, Pin pin) {
        if (pin.id() == null || pin.version() < 1) throw new ApiException(ProblemCode.VALIDATION_FAILED, "Choose an exact published view version.");
        var definition = jdbc.sql("SELECT definition::text FROM application_view_version WHERE workspace_id=:ws AND view_id=:id AND version=:version")
                .param("ws", workspace).param("id", pin.id()).param("version", pin.version()).query(String.class).optional()
                .orElseThrow(() -> new ApiException(ProblemCode.VALIDATION_FAILED, "The selected view version is not available in this workspace."));
        return new Pinned(pin.id(), pin.version(), json.parse(definition));
    }
}
