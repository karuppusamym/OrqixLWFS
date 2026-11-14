package com.orqix.catalog.git;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

import org.springframework.stereotype.Component;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * GitHub and GitHub Enterprise REST API (v2022-11-28): Git data API for trees, blobs, commits and refs; pulls and
 * reviews. Repository {@code owner/repo}. An approval counts only for the commit it was given on.
 */
@Component
public class GitHubProvider implements GitProvider {

    private static final String NAME = "GitHub";

    @Override
    public String kind() {
        return "github";
    }

    @Override
    public String defaultApiUrl() {
        return "https://api.github.com";
    }

    @Override
    public String repositoryShape() {
        return "owner/repository";
    }

    @Override
    public boolean acceptsRepository(String repository) {
        return repository.split("/").length == 2 && !repository.contains(" ");
    }

    private static Map<String, String> headers(Remote r) {
        Map<String, String> h = new LinkedHashMap<>();
        h.put("Authorization", "Bearer " + r.token());
        h.put("Accept", "application/vnd.github+json");
        h.put("X-GitHub-Api-Version", "2022-11-28");
        h.put("User-Agent", "orqix-git-sync");
        return h;
    }

    private static String repo(Remote r) {
        return GitHttp.trimSlash(r.apiUrl()) + "/repos/" + GitHttp.encodePath(r.repository());
    }

    @Override
    public String defaultBranch(Remote r) {
        return r.http().json(NAME, "reading the repository", "GET", repo(r), headers(r), null).path("default_branch")
                .asString("");
    }

    @Override
    public Optional<String> branchHead(Remote r, String branch) {
        GitHttp.Response response = r.http().send(NAME, "reading the branch", "GET",
                repo(r) + "/git/ref/heads/" + GitHttp.encodePath(branch), headers(r), null, null);
        if (response.status() == 404) {
            return Optional.empty();
        }
        if (!response.ok()) {
            throw new GitException(NAME + " reading the branch failed with HTTP " + response.status() + ".", response.status());
        }
        JsonNode ref = response.json(NAME);
        // A prefix match returns an array of refs; only an exact ref counts.
        if (ref.isArray()) {
            return Optional.empty();
        }
        return Optional.of(ref.path("object").path("sha").asString(""));
    }

    @Override
    public void createBranch(Remote r, String branch, String fromCommit) {
        ObjectNode body = GitHttp.JSON.createObjectNode().put("ref", "refs/heads/" + branch).put("sha", fromCommit);
        r.http().json(NAME, "creating the branch", "POST", repo(r) + "/git/refs", headers(r), body);
    }

    private Map<String, String> blobs(Remote r, String commit, String folder) {
        GitHttp.Response response = r.http().send(NAME, "reading the tree", "GET",
                repo(r) + "/git/trees/" + GitHttp.encode(commit) + "?recursive=1", headers(r), null, null);
        if (response.status() == 404) {
            return Map.of();
        }
        if (!response.ok()) {
            throw new GitException(NAME + " reading the tree failed with HTTP " + response.status() + ".", response.status());
        }
        JsonNode tree = response.json(NAME);
        if (tree.path("truncated").asBoolean(false)) {
            throw new GitException(NAME + " returned a truncated tree; the repository is too large to read at once.", 413);
        }
        Map<String, String> out = new LinkedHashMap<>();
        String prefix = folder + "/";
        for (JsonNode item : tree.path("tree")) {
            String path = item.path("path").asString("");
            if ("blob".equals(item.path("type").asString("")) && path.startsWith(prefix)) {
                out.put(path, item.path("sha").asString(""));
            }
        }
        return out;
    }

    @Override
    public Set<String> paths(Remote r, String commit, String folder) {
        return new TreeSet<>(blobs(r, commit, folder).keySet());
    }

    @Override
    public Map<String, byte[]> read(Remote r, String commit, Set<String> paths) {
        if (paths.isEmpty()) {
            return Map.of();
        }
        String folder = commonFolder(paths);
        Map<String, String> blobs = blobs(r, commit, folder);
        Map<String, byte[]> out = new LinkedHashMap<>();
        for (String path : paths) {
            String sha = blobs.get(path);
            if (sha == null) {
                continue;
            }
            JsonNode blob = r.http().json(NAME, "reading a file", "GET", repo(r) + "/git/blobs/" + GitHttp.encode(sha),
                    headers(r), null);
            String content = blob.path("content").asString("").replaceAll("\\s", "");
            out.put(path, Base64.getDecoder().decode(content));
        }
        return out;
    }

    static String commonFolder(Set<String> paths) {
        String first = paths.iterator().next();
        String folder = first.contains("/") ? first.substring(0, first.indexOf('/')) : "";
        return folder;
    }

    @Override
    public String commit(Remote r, String branch, String parent, Map<String, byte[]> upserts, Set<String> deletes,
            Set<String> existing, String message) {
        JsonNode parentCommit = r.http().json(NAME, "reading the parent commit", "GET",
                repo(r) + "/git/commits/" + GitHttp.encode(parent), headers(r), null);
        ObjectNode treeBody = GitHttp.JSON.createObjectNode().put("base_tree",
                parentCommit.path("tree").path("sha").asString(""));
        ArrayNode entries = treeBody.putArray("tree");
        upserts.forEach((path, bytes) -> entries.addObject().put("path", path).put("mode", "100644").put("type", "blob")
                .put("content", new String(bytes, StandardCharsets.UTF_8)));
        for (String path : deletes) {
            entries.addObject().put("path", path).put("mode", "100644").put("type", "blob").putNull("sha");
        }
        String tree = r.http().json(NAME, "writing the tree", "POST", repo(r) + "/git/trees", headers(r), treeBody)
                .path("sha").asString("");
        ObjectNode commitBody = GitHttp.JSON.createObjectNode().put("message", message).put("tree", tree);
        commitBody.putArray("parents").add(parent);
        String commit = r.http().json(NAME, "writing the commit", "POST", repo(r) + "/git/commits", headers(r), commitBody)
                .path("sha").asString("");
        ObjectNode update = GitHttp.JSON.createObjectNode().put("sha", commit).put("force", false);
        GitHttp.Response moved = r.http().send(NAME, "moving the branch", "PATCH",
                repo(r) + "/git/refs/heads/" + GitHttp.encodePath(branch), headers(r),
                GitHttp.JSON.writeValueAsBytes(update), "application/json");
        if (moved.status() == 422 || moved.status() == 409) {
            throw new GitException(NAME + " refused to move the branch: it changed since it was read.", 409);
        }
        if (!moved.ok()) {
            throw new GitException(NAME + " moving the branch failed with HTTP " + moved.status() + ".", moved.status());
        }
        return commit;
    }

    @Override
    public Optional<PullRequest> findOpenPullRequest(Remote r, String branch, String base) {
        String owner = r.repository().substring(0, r.repository().indexOf('/'));
        JsonNode list = r.http().json(NAME, "listing pull requests", "GET", repo(r) + "/pulls?state=open&head="
                + GitHttp.encode(owner + ":" + branch) + "&base=" + GitHttp.encode(base), headers(r), null);
        for (JsonNode pr : list) {
            return Optional.of(pullRequest(r, pr.path("number").asString("")));
        }
        return Optional.empty();
    }

    @Override
    public PullRequest openPullRequest(Remote r, String branch, String base, String title, String description) {
        ObjectNode body = GitHttp.JSON.createObjectNode().put("title", title).put("head", branch).put("base", base)
                .put("body", description);
        JsonNode pr = r.http().json(NAME, "opening the pull request", "POST", repo(r) + "/pulls", headers(r), body);
        return pullRequest(r, pr.path("number").asString(""));
    }

    @Override
    public PullRequest pullRequest(Remote r, String id) {
        JsonNode pr = r.http().json(NAME, "reading the pull request", "GET", repo(r) + "/pulls/" + GitHttp.encode(id),
                headers(r), null);
        String head = pr.path("head").path("sha").asString("");
        boolean merged = pr.path("merged").asBoolean(false) || pr.hasNonNull("merged_at");
        String state = merged ? "merged" : "open".equals(pr.path("state").asString("")) ? "open" : "closed";
        JsonNode reviews = r.http().json(NAME, "reading reviews", "GET",
                repo(r) + "/pulls/" + GitHttp.encode(id) + "/reviews?per_page=100", headers(r), null);
        // The latest decisive review per person; an approval counts only when it was given on the head commit.
        Map<String, JsonNode> latest = new LinkedHashMap<>();
        for (JsonNode review : reviews) {
            String state1 = review.path("state").asString("");
            if (state1.equals("APPROVED") || state1.equals("CHANGES_REQUESTED") || state1.equals("DISMISSED")) {
                latest.put(review.path("user").path("login").asString(""), review);
            }
        }
        Set<String> approvers = new LinkedHashSet<>();
        latest.forEach((login, review) -> {
            if ("APPROVED".equals(review.path("state").asString("")) && head.equals(review.path("commit_id").asString(""))
                    && !login.isEmpty()) {
                approvers.add(login);
            }
        });
        return new PullRequest(pr.path("number").asString(id), pr.path("html_url").asString(null), state,
                pr.path("head").path("ref").asString(""), head,
                pr.path("merge_commit_sha").asString(null), List.copyOf(new ArrayList<>(approvers)));
    }
}
