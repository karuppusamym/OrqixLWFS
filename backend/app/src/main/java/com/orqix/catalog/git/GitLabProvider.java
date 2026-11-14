package com.orqix.catalog.git;

import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
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
 * GitLab (gitlab.com or self-managed) REST API v4: repository tree, blobs, commits with actions, branches, merge
 * requests and approvals. Repository is the project path ({@code group/project}, subgroups allowed). Approvals count
 * only while the merge request's head is the commit asked about (GitLab approves the merge request, not a commit).
 */
@Component
public class GitLabProvider implements GitProvider {

    private static final String NAME = "GitLab";

    @Override
    public String kind() {
        return "gitlab";
    }

    @Override
    public String defaultApiUrl() {
        return "https://gitlab.com/api/v4";
    }

    @Override
    public String repositoryShape() {
        return "group/project (subgroups allowed)";
    }

    @Override
    public boolean acceptsRepository(String repository) {
        return repository.split("/").length >= 2 && !repository.contains(" ");
    }

    private static Map<String, String> headers(Remote r) {
        Map<String, String> h = new LinkedHashMap<>();
        h.put("PRIVATE-TOKEN", r.token());
        h.put("Accept", "application/json");
        return h;
    }

    private static String project(Remote r) {
        return GitHttp.trimSlash(r.apiUrl()) + "/projects/" + GitHttp.encode(r.repository());
    }

    @Override
    public String defaultBranch(Remote r) {
        return r.http().json(NAME, "reading the project", "GET", project(r), headers(r), null).path("default_branch")
                .asString("");
    }

    @Override
    public Optional<String> branchHead(Remote r, String branch) {
        GitHttp.Response response = r.http().send(NAME, "reading the branch", "GET",
                project(r) + "/repository/branches/" + GitHttp.encode(branch), headers(r), null, null);
        if (response.status() == 404) {
            return Optional.empty();
        }
        if (!response.ok()) {
            throw new GitException(NAME + " reading the branch failed with HTTP " + response.status() + ".", response.status());
        }
        return Optional.of(response.json(NAME).path("commit").path("id").asString(""));
    }

    @Override
    public void createBranch(Remote r, String branch, String fromCommit) {
        r.http().json(NAME, "creating the branch", "POST", project(r) + "/repository/branches?branch="
                + GitHttp.encode(branch) + "&ref=" + GitHttp.encode(fromCommit), headers(r), null);
    }

    private Map<String, String> blobs(Remote r, String commit, String folder) {
        Map<String, String> out = new LinkedHashMap<>();
        String page = "1";
        for (int guard = 0; guard < 20 && page != null && !page.isBlank(); guard++) {
            GitHttp.Response response = r.http().send(NAME, "reading the tree", "GET", project(r)
                    + "/repository/tree?recursive=true&per_page=100&ref=" + GitHttp.encode(commit) + "&path="
                    + GitHttp.encode(folder) + "&page=" + GitHttp.encode(page), headers(r), null, null);
            if (response.status() == 404) {
                return out;
            }
            if (!response.ok()) {
                throw new GitException(NAME + " reading the tree failed with HTTP " + response.status() + ".", response.status());
            }
            for (JsonNode item : response.json(NAME)) {
                if ("blob".equals(item.path("type").asString(""))) {
                    out.put(item.path("path").asString(""), item.path("id").asString(""));
                }
            }
            page = response.header("X-Next-Page").orElse(null);
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
        Map<String, String> blobs = blobs(r, commit, GitHubProvider.commonFolder(paths));
        Map<String, byte[]> out = new LinkedHashMap<>();
        for (String path : paths) {
            String id = blobs.get(path);
            if (id != null) {
                out.put(path, r.http().expect(NAME, "reading a file", "GET",
                        project(r) + "/repository/blobs/" + GitHttp.encode(id) + "/raw", headers(r), null, null).body());
            }
        }
        return out;
    }

    @Override
    public String commit(Remote r, String branch, String parent, Map<String, byte[]> upserts, Set<String> deletes,
            Set<String> existing, String message) {
        // GitLab has no compare-and-swap on a branch for commits: check the tip right before writing.
        String head = branchHead(r, branch).orElseThrow(() -> new GitException(NAME + ": the branch no longer exists.", 409));
        if (!head.equals(parent)) {
            throw new GitException(NAME + " refused the commit: the branch changed since it was read.", 409);
        }
        ObjectNode body = GitHttp.JSON.createObjectNode().put("branch", branch).put("commit_message", message);
        ArrayNode actions = body.putArray("actions");
        upserts.forEach((path, bytes) -> actions.addObject().put("action", existing.contains(path) ? "update" : "create")
                .put("file_path", path).put("encoding", "base64").put("content", Base64.getEncoder().encodeToString(bytes)));
        deletes.forEach(path -> actions.addObject().put("action", "delete").put("file_path", path));
        return r.http().json(NAME, "writing the commit", "POST", project(r) + "/repository/commits", headers(r), body)
                .path("id").asString("");
    }

    @Override
    public Optional<PullRequest> findOpenPullRequest(Remote r, String branch, String base) {
        JsonNode list = r.http().json(NAME, "listing merge requests", "GET", project(r)
                + "/merge_requests?state=opened&source_branch=" + GitHttp.encode(branch) + "&target_branch="
                + GitHttp.encode(base), headers(r), null);
        for (JsonNode mr : list) {
            return Optional.of(pullRequest(r, mr.path("iid").asString("")));
        }
        return Optional.empty();
    }

    @Override
    public PullRequest openPullRequest(Remote r, String branch, String base, String title, String description) {
        ObjectNode body = GitHttp.JSON.createObjectNode().put("source_branch", branch).put("target_branch", base)
                .put("title", title).put("description", description).put("remove_source_branch", false);
        JsonNode mr = r.http().json(NAME, "opening the merge request", "POST", project(r) + "/merge_requests",
                headers(r), body);
        return pullRequest(r, mr.path("iid").asString(""));
    }

    @Override
    public PullRequest pullRequest(Remote r, String id) {
        String url = project(r) + "/merge_requests/" + GitHttp.encode(id);
        JsonNode mr = r.http().json(NAME, "reading the merge request", "GET", url, headers(r), null);
        String state = switch (mr.path("state").asString("")) {
            case "merged" -> "merged";
            case "opened" -> "open";
            default -> "closed";
        };
        JsonNode approvals = r.http().json(NAME, "reading approvals", "GET", url + "/approvals", headers(r), null);
        List<String> approvers = new ArrayList<>();
        for (JsonNode a : approvals.path("approved_by")) {
            String user = a.path("user").path("username").asString("");
            if (!user.isEmpty()) {
                approvers.add(user);
            }
        }
        String merge = mr.hasNonNull("merge_commit_sha") ? mr.path("merge_commit_sha").asString(null)
                : mr.path("squash_commit_sha").asString(null);
        return new PullRequest(mr.path("iid").asString(id), mr.path("web_url").asString(null), state,
                mr.path("source_branch").asString(""),
                mr.path("sha").asString(""), merge, List.copyOf(approvers));
    }
}
