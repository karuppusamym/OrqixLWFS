package com.orqix.support;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.any;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import com.github.tomakehurst.wiremock.extension.ResponseDefinitionTransformerV2;
import com.github.tomakehurst.wiremock.http.Request;
import com.github.tomakehurst.wiremock.http.ResponseDefinition;
import com.github.tomakehurst.wiremock.stubbing.ServeEvent;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * An in-memory Git host behind WireMock (docs/build/S147): enough of the GitHub REST API (under {@code /github}) and
 * the GitLab REST API v4 (under {@code /gitlab/api/v4}) for Git sync to push, open pull/merge requests, read trees and
 * files, and for tests to approve, merge and edit as a reviewer would. Commits are immutable snapshots of every file;
 * a branch points at a commit. Each request must carry the repository's token.
 */
public final class GitStandIn implements ResponseDefinitionTransformerV2 {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static GitStandIn instance;
    private static WireMockServer server;

    /** One repository. */
    public static final class Repo {
        final String token;
        final Map<String, Map<String, byte[]>> commits = new HashMap<>();
        final Map<String, String> parents = new HashMap<>();
        final Map<String, String> branches = new LinkedHashMap<>();
        final Map<String, byte[]> blobs = new HashMap<>();
        final Map<String, Map<String, byte[]>> trees = new HashMap<>();
        public final List<Pr> prs = new ArrayList<>();
        final String defaultBranch;

        Repo(String token, String defaultBranch) {
            this.token = token;
            this.defaultBranch = defaultBranch;
        }

        String commit(String parent, Map<String, byte[]> files) {
            String sha = sha1(UUID.randomUUID().toString());
            commits.put(sha, new TreeMap<>(files));
            parents.put(sha, parent);
            files.values().forEach(b -> blobs.put(sha1(b), b));
            return sha;
        }

        public Map<String, byte[]> files(String branchOrCommit) {
            String sha = branches.getOrDefault(branchOrCommit, branchOrCommit);
            return commits.get(sha);
        }

        public String head(String branch) {
            return branches.get(branch);
        }

        /** A reviewer's commit on a branch (also clears GitLab approvals, as "reset approvals on push" does). */
        public String push(String branch, Map<String, byte[]> changes, List<String> deletes) {
            Map<String, byte[]> files = new TreeMap<>(files(branch));
            files.putAll(changes);
            deletes.forEach(files::remove);
            String sha = commit(branches.get(branch), files);
            branches.put(branch, sha);
            prs.stream().filter(p -> p.source.equals(branch) && p.state.equals("open")).forEach(p -> p.gitlabApprovals.clear());
            return sha;
        }

        public int commitsCount() {
            return commits.size();
        }

        public Pr pr(int number) {
            return prs.get(number - 1);
        }
    }

    /** A pull or merge request. */
    public static final class Pr {
        public final int number;
        final String source;
        final String target;
        public String state = "open";
        public String mergeCommit;
        final List<String[]> reviews = new CopyOnWriteArrayList<>(); // login, state, commit
        final List<String> gitlabApprovals = new CopyOnWriteArrayList<>();
        final Repo repo;

        Pr(Repo repo, int number, String source, String target) {
            this.repo = repo;
            this.number = number;
            this.source = source;
            this.target = target;
        }

        /** A review approving the current head (GitHub) or an approval of the merge request (GitLab). */
        public void approve(String login) {
            reviews.add(new String[] {login, "APPROVED", repo.head(source)});
            gitlabApprovals.add(login);
        }

        /** Merges the source branch into the target with a merge commit. */
        public String merge() {
            String sha = repo.commit(repo.head(target), repo.files(source));
            repo.branches.put(target, sha);
            state = "merged";
            mergeCommit = sha;
            return sha;
        }
    }

    private final Map<String, Repo> repos = new HashMap<>();
    /** Every request seen, as "METHOD path" with the auth header value. */
    public final List<String[]> requests = new CopyOnWriteArrayList<>();

    public static synchronized GitStandIn get() {
        if (server == null) {
            instance = new GitStandIn();
            server = new WireMockServer(options().dynamicPort().extensions(instance));
            server.start();
            server.stubFor(any(anyUrl()).willReturn(aResponse().withStatus(200)));
        }
        return instance;
    }

    public static int port() {
        get();
        return server.port();
    }

    public String baseUrl() {
        return "http://localhost:" + port();
    }

    /** A new repository whose default branch holds a README. */
    public synchronized Repo create(String provider, String repository, String token) {
        Repo repo = new Repo(token, "main");
        String sha = repo.commit(null, Map.of("README.md", "Workflows\n".getBytes(StandardCharsets.UTF_8)));
        repo.branches.put("main", sha);
        repos.put(provider + ":" + repository, repo);
        return repo;
    }

    @Override
    public String getName() {
        return "git-stand-in";
    }

    @Override
    public boolean applyGlobally() {
        return true;
    }

    @Override
    public synchronized ResponseDefinition transform(ServeEvent event) {
        Request request = event.getRequest();
        String url = request.getUrl();
        String path = url.contains("?") ? url.substring(0, url.indexOf('?')) : url;
        Map<String, String> query = query(url);
        String method = request.getMethod().getName();
        try {
            if (path.startsWith("/github/repos/")) {
                requests.add(new String[] {method + " " + path, request.getHeader("Authorization")});
                return github(method, path.substring("/github/repos/".length()), query, request);
            }
            if (path.startsWith("/gitlab/api/v4/projects/")) {
                requests.add(new String[] {method + " " + path, request.getHeader("PRIVATE-TOKEN")});
                return gitlab(method, path.substring("/gitlab/api/v4/projects/".length()), query, request);
            }
            return status(404, "{}");
        } catch (RuntimeException e) {
            return status(500, "{\"message\":\"stand-in error\"}");
        }
    }

    // ------------------------------------------------------------------ GitHub

    private ResponseDefinition github(String method, String rest, Map<String, String> query, Request request) {
        String[] parts = rest.split("/", 3);
        Repo repo = repos.get("github:" + parts[0] + "/" + parts[1]);
        if (repo == null) {
            return status(404, "{}");
        }
        if (!("Bearer " + repo.token).equals(request.getHeader("Authorization"))) {
            return status(401, "{\"message\":\"Bad credentials\"}");
        }
        String tail = parts.length > 2 ? parts[2] : "";
        ObjectNode out = JSON.createObjectNode();
        if (tail.isEmpty() && method.equals("GET")) {
            return json(200, out.put("default_branch", repo.defaultBranch));
        }
        if (tail.startsWith("git/ref/heads/") && method.equals("GET")) {
            String sha = repo.head(decode(tail.substring("git/ref/heads/".length())));
            if (sha == null) {
                return status(404, "{}");
            }
            out.put("ref", "refs/heads/x").putObject("object").put("sha", sha);
            return json(200, out);
        }
        if (tail.equals("git/refs") && method.equals("POST")) {
            JsonNode body = body(request);
            repo.branches.put(body.path("ref").asString().substring("refs/heads/".length()), body.path("sha").asString());
            return json(201, out);
        }
        if (tail.startsWith("git/refs/heads/") && method.equals("PATCH")) {
            String branch = decode(tail.substring("git/refs/heads/".length()));
            String sha = body(request).path("sha").asString();
            if (!repo.head(branch).equals(repo.parents.get(sha))) {
                return status(422, "{\"message\":\"Update is not a fast forward\"}");
            }
            repo.branches.put(branch, sha);
            return json(200, out);
        }
        if (tail.startsWith("git/trees/") && method.equals("GET")) {
            String sha = tail.substring("git/trees/".length());
            Map<String, byte[]> files = repo.commits.containsKey(sha) ? repo.commits.get(sha) : repo.trees.get(sha);
            if (files == null) {
                return status(404, "{}");
            }
            ArrayNode tree = out.put("sha", sha).put("truncated", false).putArray("tree");
            files.forEach((p, b) -> tree.addObject().put("path", p).put("type", "blob").put("sha", sha1(b)));
            return json(200, out);
        }
        if (tail.startsWith("git/blobs/") && method.equals("GET")) {
            byte[] blob = repo.blobs.get(tail.substring("git/blobs/".length()));
            return blob == null ? status(404, "{}")
                    : json(200, out.put("content", Base64.getMimeEncoder().encodeToString(blob)).put("encoding", "base64"));
        }
        if (tail.startsWith("git/commits/") && method.equals("GET")) {
            String sha = tail.substring("git/commits/".length());
            String tree = "t" + sha;
            repo.trees.put(tree, repo.commits.get(sha));
            out.put("sha", sha).putObject("tree").put("sha", tree);
            return json(200, out);
        }
        if (tail.equals("git/trees") && method.equals("POST")) {
            JsonNode body = body(request);
            Map<String, byte[]> files = new TreeMap<>(repo.trees.get(body.path("base_tree").asString()));
            for (JsonNode entry : body.path("tree")) {
                if (entry.path("sha").isNull()) {
                    files.remove(entry.path("path").asString());
                } else {
                    byte[] bytes = entry.path("content").asString().getBytes(StandardCharsets.UTF_8);
                    files.put(entry.path("path").asString(), bytes);
                    repo.blobs.put(sha1(bytes), bytes);
                }
            }
            String tree = "t" + sha1(UUID.randomUUID().toString());
            repo.trees.put(tree, files);
            return json(201, out.put("sha", tree));
        }
        if (tail.equals("git/commits") && method.equals("POST")) {
            JsonNode body = body(request);
            String sha = repo.commit(body.path("parents").get(0).asString(), repo.trees.get(body.path("tree").asString()));
            return json(201, out.put("sha", sha));
        }
        if (tail.equals("pulls") && method.equals("GET")) {
            String head = query.getOrDefault("head", "");
            String branch = head.contains(":") ? head.substring(head.indexOf(':') + 1) : head;
            ArrayNode list = JSON.createArrayNode();
            repo.prs.stream().filter(p -> p.state.equals("open") && p.source.equals(branch)
                    && p.target.equals(query.getOrDefault("base", p.target))).forEach(p -> list.addObject().put("number", p.number));
            return json(200, list);
        }
        if (tail.equals("pulls") && method.equals("POST")) {
            JsonNode body = body(request);
            Pr pr = new Pr(repo, repo.prs.size() + 1, body.path("head").asString(), body.path("base").asString());
            repo.prs.add(pr);
            return json(201, out.put("number", pr.number));
        }
        if (tail.matches("pulls/\\d+") && method.equals("GET")) {
            Pr pr = repo.pr(Integer.parseInt(tail.substring("pulls/".length())));
            out.put("number", pr.number).put("state", pr.state.equals("open") ? "open" : "closed")
                    .put("merged", pr.state.equals("merged")).put("html_url", "https://github.example/pull/" + pr.number)
                    .put("merge_commit_sha", pr.mergeCommit);
            out.putObject("head").put("sha", repo.head(pr.source)).put("ref", pr.source);
            return json(200, out);
        }
        if (tail.matches("pulls/\\d+/reviews") && method.equals("GET")) {
            Pr pr = repo.pr(Integer.parseInt(tail.split("/")[1]));
            ArrayNode list = JSON.createArrayNode();
            for (String[] r : pr.reviews) {
                ObjectNode review = list.addObject().put("state", r[1]).put("commit_id", r[2]);
                review.putObject("user").put("login", r[0]);
            }
            return json(200, list);
        }
        return status(404, "{}");
    }

    // ------------------------------------------------------------------ GitLab

    private ResponseDefinition gitlab(String method, String rest, Map<String, String> query, Request request) {
        int slash = rest.indexOf('/');
        String id = decode(slash < 0 ? rest : rest.substring(0, slash));
        String tail = slash < 0 ? "" : rest.substring(slash + 1);
        Repo repo = repos.get("gitlab:" + id);
        if (repo == null) {
            return status(404, "{}");
        }
        if (!repo.token.equals(request.getHeader("PRIVATE-TOKEN"))) {
            return status(401, "{\"message\":\"401 Unauthorized\"}");
        }
        ObjectNode out = JSON.createObjectNode();
        if (tail.isEmpty() && method.equals("GET")) {
            return json(200, out.put("default_branch", repo.defaultBranch));
        }
        if (tail.startsWith("repository/branches/") && method.equals("GET")) {
            String branch = decode(tail.substring("repository/branches/".length()));
            String sha = repo.head(branch);
            if (sha == null) {
                return status(404, "{}");
            }
            out.put("name", branch).putObject("commit").put("id", sha);
            return json(200, out);
        }
        if (tail.equals("repository/branches") && method.equals("POST")) {
            repo.branches.put(query.get("branch"), query.get("ref"));
            return json(201, out.put("name", query.get("branch")));
        }
        if (tail.equals("repository/tree") && method.equals("GET")) {
            Map<String, byte[]> files = repo.files(query.get("ref"));
            if (files == null) {
                return status(404, "{}");
            }
            String prefix = query.getOrDefault("path", "") + "/";
            ArrayNode list = JSON.createArrayNode();
            files.forEach((p, b) -> {
                if (p.startsWith(prefix)) {
                    list.addObject().put("id", sha1(b)).put("path", p).put("type", "blob");
                }
            });
            if (list.isEmpty()) {
                return status(404, "{\"message\":\"404 Tree Not Found\"}");
            }
            return json(200, list);
        }
        if (tail.matches("repository/blobs/[0-9a-f]+/raw") && method.equals("GET")) {
            byte[] blob = repo.blobs.get(tail.split("/")[2]);
            return blob == null ? status(404, "{}")
                    : ResponseDefinitionBuilder.responseDefinition().withStatus(200).withBody(blob).build();
        }
        if (tail.equals("repository/commits") && method.equals("POST")) {
            JsonNode body = body(request);
            String branch = body.path("branch").asString();
            Map<String, byte[]> files = new TreeMap<>(repo.files(branch));
            for (JsonNode action : body.path("actions")) {
                String file = action.path("file_path").asString();
                switch (action.path("action").asString()) {
                    case "create" -> {
                        if (files.containsKey(file)) {
                            return status(400, "{\"message\":\"A file with this name already exists\"}");
                        }
                        files.put(file, Base64.getDecoder().decode(action.path("content").asString()));
                    }
                    case "update" -> {
                        if (!files.containsKey(file)) {
                            return status(400, "{\"message\":\"A file with this name doesn't exist\"}");
                        }
                        files.put(file, Base64.getDecoder().decode(action.path("content").asString()));
                    }
                    case "delete" -> files.remove(file);
                    default -> {
                        return status(400, "{}");
                    }
                }
            }
            String sha = repo.commit(repo.head(branch), files);
            repo.branches.put(branch, sha);
            repo.prs.stream().filter(p -> p.source.equals(branch) && p.state.equals("open")).forEach(p -> p.gitlabApprovals.clear());
            return json(201, out.put("id", sha));
        }
        if (tail.equals("merge_requests") && method.equals("GET")) {
            ArrayNode list = JSON.createArrayNode();
            repo.prs.stream().filter(p -> p.state.equals("open") && p.source.equals(query.get("source_branch"))
                    && p.target.equals(query.getOrDefault("target_branch", p.target)))
                    .forEach(p -> list.addObject().put("iid", p.number));
            return json(200, list);
        }
        if (tail.equals("merge_requests") && method.equals("POST")) {
            JsonNode body = body(request);
            Pr pr = new Pr(repo, repo.prs.size() + 1, body.path("source_branch").asString(),
                    body.path("target_branch").asString());
            repo.prs.add(pr);
            return json(201, out.put("iid", pr.number));
        }
        if (tail.matches("merge_requests/\\d+") && method.equals("GET")) {
            Pr pr = repo.pr(Integer.parseInt(tail.split("/")[1]));
            out.put("iid", pr.number).put("state", pr.state.equals("open") ? "opened" : pr.state)
                    .put("sha", repo.head(pr.source)).put("merge_commit_sha", pr.mergeCommit)
                    .put("source_branch", pr.source).put("web_url", "https://gitlab.example/-/merge_requests/" + pr.number);
            return json(200, out);
        }
        if (tail.matches("merge_requests/\\d+/approvals") && method.equals("GET")) {
            Pr pr = repo.pr(Integer.parseInt(tail.split("/")[1]));
            ArrayNode by = out.put("approved", !pr.gitlabApprovals.isEmpty()).putArray("approved_by");
            pr.gitlabApprovals.forEach(u -> by.addObject().putObject("user").put("username", u));
            return json(200, out);
        }
        return status(404, "{}");
    }

    // ------------------------------------------------------------------ helpers

    private static JsonNode body(Request request) {
        return JSON.readTree(request.getBody());
    }

    private static ResponseDefinition json(int status, JsonNode body) {
        return ResponseDefinitionBuilder.responseDefinition().withStatus(status).withHeader("Content-Type", "application/json")
                .withBody(JSON.writeValueAsBytes(body)).build();
    }

    private static ResponseDefinition status(int status, String body) {
        return ResponseDefinitionBuilder.responseDefinition().withStatus(status).withHeader("Content-Type", "application/json")
                .withBody(body).build();
    }

    private static Map<String, String> query(String url) {
        Map<String, String> out = new HashMap<>();
        if (!url.contains("?")) {
            return out;
        }
        for (String pair : url.substring(url.indexOf('?') + 1).split("&")) {
            String[] kv = pair.split("=", 2);
            out.put(decode(kv[0]), kv.length > 1 ? decode(kv[1]) : "");
        }
        return out;
    }

    private static String decode(String value) {
        return URLDecoder.decode(value, StandardCharsets.UTF_8);
    }

    static String sha1(String text) {
        return sha1(text.getBytes(StandardCharsets.UTF_8));
    }

    static String sha1(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(bytes));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
