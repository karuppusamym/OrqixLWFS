package com.orqix.catalog.internal;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.orqix.compiler.Severity;
import com.orqix.compiler.ValidationIssue;
import com.orqix.shared.json.Json;

import tools.jackson.databind.JsonNode;

/**
 * Checks a workflow's versioned references to shared assets (docs/build/S57) against the workspace's current assets at
 * validation and publish. A reference is never resolved silently: a missing (deleted, or from another workspace),
 * archived or differing asset is a blocker, and a newer version than the pinned one is a warning the publisher must
 * acknowledge. Connection pins ({@code dependencies.connections}) get the archive and newer-version checks; their
 * existence is the compiler's rule. The check reads only the workflow's own workspace and returns findings about keys
 * and versions, never asset content, so it discloses nothing beyond what a workflow reader may already list.
 */
@Component
public class AssetReferenceCheck {

    public static final String MISSING = "WF_ASSET_MISSING";
    public static final String ARCHIVED = "WF_ASSET_ARCHIVED";
    public static final String DIVERGED = "WF_ASSET_DIVERGED";
    public static final String STALE = "WF_ASSET_STALE";
    public static final String INVALID = "WF_ASSET_REFERENCE_INVALID";

    private final JdbcClient jdbc;
    private final SharedAssets assets;
    private final Json json;
    private final RequestViewCheck requestViews;

    public AssetReferenceCheck(JdbcClient jdbc, SharedAssets assets, Json json, RequestViewCheck requestViews) {
        this.jdbc = jdbc;
        this.assets = assets;
        this.json = json;
        this.requestViews = requestViews;
    }

    private record Asset(int latest, boolean archived, Map<Integer, String> hashes) {
    }

    /** Findings to add to the compiler's own; empty when the definition references nothing or cannot be read. */
    @Transactional(readOnly = true)
    public List<ValidationIssue> issues(UUID ws, String definitionJson) {
        JsonNode root;
        try {
            root = json.parse(definitionJson);
        } catch (RuntimeException e) {
            return List.of();
        }
        List<ValidationIssue> issues = new ArrayList<>();
        JsonNode refs = root.path("dependencies").path("assets");
        Set<String> targets = new HashSet<>();
        for (int i = 0; i < refs.size(); i++) {
            JsonNode ref = refs.get(i);
            String kind = ref.path("kind").asString("");
            String key = ref.path("key").asString("");
            String target = ref.path("target").asString("");
            int version = ref.path("version").asInt(0);
            String path = "/dependencies/assets/" + i;
            if (!List.of(SharedAssets.DECISION, SharedAssets.TEMPLATE).contains(kind) || key.isEmpty() || version < 1) {
                continue; // the schema reports the shape
            }
            String noun = SharedAssets.DECISION.equals(kind) ? "shared decision" : "shared document template";
            if (!targets.add(kind + "|" + target)) {
                issues.add(blocker(INVALID, "\"" + target + "\" is linked to more than one " + noun + ".", path, null));
                continue;
            }
            Target local = SharedAssets.DECISION.equals(kind) ? decisionTarget(root, target) : templateTarget(root, target);
            if (local == null) {
                issues.add(blocker(INVALID, "The link to the " + noun + " \"" + key + "\" names \"" + target
                        + "\", which is not " + (SharedAssets.DECISION.equals(kind) ? "a decision table" : "a Generate a document step")
                        + " in this workflow. Remove the link or restore the element.", path, null));
                continue;
            }
            Optional<Asset> asset = load(ws, kind, key);
            if (asset.isEmpty() || !asset.get().hashes().containsKey(version)) {
                issues.add(blocker(MISSING, "The " + noun + " \"" + key + "\" version " + version + " does not exist in this"
                        + " workspace. It may have been removed, or the workflow came from another workspace. Link an"
                        + " existing asset or remove the link.", path, local.nodeId()));
                continue;
            }
            Asset found = asset.get();
            if (found.archived()) {
                issues.add(blocker(ARCHIVED, "The " + noun + " \"" + key + "\" is archived. Restore it, or remove the link"
                        + " and keep this workflow's own copy.", path, local.nodeId()));
            }
            if (!found.hashes().get(version).equals(local.hash())) {
                issues.add(blocker(DIVERGED, "\"" + target + "\" no longer matches version " + version + " of the " + noun
                        + " \"" + key + "\". Update it from the shared version, or remove the link to keep the edited copy.",
                        path, local.nodeId()));
            }
            if (version < found.latest()) {
                issues.add(warning("\"" + target + "\" uses version " + version + " of the " + noun + " \"" + key
                        + "\"; version " + found.latest() + " is available. Publishing keeps version " + version + ".",
                        path, local.nodeId()));
            }
        }
        connectionIssues(ws, root, issues);
        issues.addAll(requestViews.issues(ws, root));
        return issues;
    }

    private void connectionIssues(UUID ws, JsonNode root, List<ValidationIssue> issues) {
        JsonNode pins = root.path("dependencies").path("connections");
        if (pins.isEmpty()) {
            return;
        }
        record Header(int latest, boolean archived) {
        }
        Map<String, Header> headers = new HashMap<>();
        jdbc.sql("""
                SELECT c.key, max(c.version) AS latest, bool_or(h.archived_at IS NOT NULL) AS archived
                FROM connection_definition c
                LEFT JOIN connection_asset h ON h.workspace_id = c.workspace_id AND h.connection_key = c.key
                WHERE c.workspace_id = :ws GROUP BY c.key
                """).param("ws", ws).query((rs, n) -> headers.put(rs.getString("key"),
                        new Header(rs.getInt("latest"), rs.getBoolean("archived")))).list();
        for (int i = 0; i < pins.size(); i++) {
            String key = pins.get(i).path("key").asString("");
            int version = pins.get(i).path("definitionVersion").asInt(0);
            Header header = headers.get(key);
            if (header == null) {
                continue; // an unknown connection is the compiler's blocker
            }
            String path = "/dependencies/connections/" + i;
            if (header.archived()) {
                issues.add(blocker(ARCHIVED, "The connection \"" + key + "\" is archived. Ask a connection manager to"
                        + " restore it, or stop using it in this workflow.", path, null));
            }
            if (version >= 1 && version < header.latest()) {
                issues.add(warning("This workflow uses version " + version + " of the connection \"" + key + "\"; version "
                        + header.latest() + " is available. Publishing keeps version " + version + ".", path, null));
            }
        }
    }

    private record Target(String hash, String nodeId) {
    }

    private Target decisionTarget(JsonNode root, String key) {
        for (JsonNode table : root.path("decisions")) {
            if (key.equals(table.path("key").asString(null))) {
                return new Target(assets.decisionHash(table), null);
            }
        }
        return null;
    }

    private static Target templateTarget(JsonNode root, String nodeId) {
        for (JsonNode node : root.path("nodes")) {
            if (nodeId.equals(node.path("id").asString(null)) && "generateDocument".equals(node.path("type").asString(null))) {
                JsonNode config = node.path("config");
                return new Target(templateHash(config.path("template").asString(""), config.path("format").asString("")),
                        nodeId);
            }
        }
        return null;
    }

    private Optional<Asset> load(UUID ws, String kind, String key) {
        String sql = SharedAssets.DECISION.equals(kind)
                ? """
                  SELECT a.latest_version, a.archived_at IS NOT NULL AS archived, v.version, v.sha256
                  FROM decision_asset a JOIN decision_asset_version v ON v.asset_id = a.id
                  WHERE a.workspace_id = :ws AND a.key = :key"""
                : """
                  SELECT t.latest_version, t.archived_at IS NOT NULL AS archived, v.version, v.sha256
                  FROM document_template t JOIN document_template_version v ON v.template_id = t.id
                  WHERE t.workspace_id = :ws AND t.key = :key""";
        Map<Integer, String> hashes = new HashMap<>();
        int[] latest = {0};
        boolean[] archived = {false};
        jdbc.sql(sql).param("ws", ws).param("key", key).query((rs, n) -> {
            latest[0] = rs.getInt("latest_version");
            archived[0] = rs.getBoolean("archived");
            return hashes.put(rs.getInt("version"), rs.getString("sha256"));
        }).list();
        return latest[0] == 0 ? Optional.empty() : Optional.of(new Asset(latest[0], archived[0], hashes));
    }

    /** Same hash as the template registry: SHA-256 of format, newline, body. */
    static String templateHash(String body, String format) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest((format + "\n" + body).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static ValidationIssue blocker(String code, String message, String path, String nodeId) {
        return new ValidationIssue(code, Severity.BLOCKER, message, path, nodeId, null);
    }

    private static ValidationIssue warning(String message, String path, String nodeId) {
        return new ValidationIssue(STALE, Severity.WARNING, message, path, nodeId, null);
    }

    public static boolean hasBlocker(List<ValidationIssue> issues) {
        return issues.stream().anyMatch(i -> i.severity() == Severity.BLOCKER);
    }
}
