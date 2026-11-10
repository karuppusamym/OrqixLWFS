package com.orqix.catalog.git;

import java.nio.charset.StandardCharsets;
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
 * Azure DevOps Services / Server Git REST API 7.1: items, blobs, pushes (with the expected old commit, so a moved
 * branch is refused), refs and pull requests. Repository {@code organization/project/repository}; a personal access
 * token goes in Basic authentication with an empty user name. A reviewer vote of 5 (approved with suggestions) or 10
 * (approved) is an approval; Azure DevOps resets votes on new pushes when the branch policy says so.
 */
@Component
public class AzureDevOpsProvider implements GitProvider {

    private static final String NAME = "Azure DevOps";
    private static final String VERSION = "api-version=7.1";
    private static final String ZERO = "0000000000000000000000000000000000000000";

    @Override
    public String kind() {
        return "azure-devops";
    }

    @Override
    public String defaultApiUrl() {
        return "https://dev.azure.com";
    }

    @Override
    public String repositoryShape() {
        return "organization/project/repository";
    }

    @Override
    public boolean acceptsRepository(String repository) {
        return repository.split("/").length == 3;
    }

    private static Map<String, String> headers(Remote r) {
        Map<String, String> h = new LinkedHashMap<>();
        h.put("Authorization", "Basic " + Base64.getEncoder().encodeToString((":" + r.token()).getBytes(StandardCharsets.UTF_8)));
        h.put("Accept", "application/json");
        return h;
    }

    private static String[] parts(Remote r) {
        return r.repository().split("/");
    }

    private static String repo(Remote r) {
        String[] p = parts(r);
        return GitHttp.trimSlash(r.apiUrl()) + "/" + GitHttp.encode(p[0]) + "/" + GitHttp.encode(p[1])
                + "/_apis/git/repositories/" + GitHttp.encode(p[2]);
    }

    private static String q(String url) {
        return url + (url.contains("?") ? "&" : "?") + VERSION;
    }

    @Override
    public String defaultBranch(Remote r) {
        String ref = r.http().json(NAME, "reading the repository", "GET", q(repo(r)), headers(r), null)
                .path("defaultBranch").asString("");
        return ref.startsWith("refs/heads/") ? ref.substring("refs/heads/".length()) : ref;
    }

    @Override
    public Optional<String> branchHead(Remote r, String branch) {
        JsonNode refs = r.http().json(NAME, "reading the branch", "GET",
                q(repo(r) + "/refs?filter=" + GitHttp.encode("heads/" + branch)), headers(r), null);
        for (JsonNode ref : refs.path("value")) {
            if (("refs/heads/" + branch).equals(ref.path("name").asString(""))) {
                return Optional.of(ref.path("objectId").asString(""));
            }
        }
        return Optional.empty();
    }

    @Override
    public void createBranch(Remote r, String branch, String fromCommit) {
        ArrayNode body = GitHttp.JSON.createArrayNode();
        body.addObject().put("name", "refs/heads/" + branch).put("oldObjectId", ZERO).put("newObjectId", fromCommit);
        JsonNode result = r.http().json(NAME, "creating the branch", "POST", q(repo(r) + "/refs"), headers(r), body);
        for (JsonNode update : result.path("value")) {
            if (!update.path("success").asBoolean(true)) {
                throw new GitException(NAME + " refused to create the branch.", 409);
            }
        }
    }

    private Map<String, String> blobs(Remote r, String commit, String folder) {
        GitHttp.Response response = r.http().send(NAME, "reading the tree", "GET", q(repo(r) + "/items?scopePath="
                + GitHttp.encode("/" + folder) + "&recursionLevel=Full&versionDescriptor.version=" + GitHttp.encode(commit)
                + "&versionDescriptor.versionType=commit"), headers(r), null, null);
        if (response.status() == 404) {
            return Map.of();
        }
        if (!response.ok()) {
            throw new GitException(NAME + " reading the tree failed with HTTP " + response.status() + ".", response.status());
        }
        Map<String, String> out = new LinkedHashMap<>();
        for (JsonNode item : response.json(NAME).path("value")) {
            if (!item.path("isFolder").asBoolean(false) && "blob".equals(item.path("gitObjectType").asString("blob"))) {
                String path = item.path("path").asString("");
                out.put(path.startsWith("/") ? path.substring(1) : path, item.path("objectId").asString(""));
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
        Map<String, String> blobs = blobs(r, commit, GitHubProvider.commonFolder(paths));
        Map<String, byte[]> out = new LinkedHashMap<>();
        Map<String, String> octet = new LinkedHashMap<>(headers(r));
        octet.put("Accept", "application/octet-stream");
        for (String path : paths) {
            String id = blobs.get(path);
            if (id != null) {
                out.put(path, r.http().expect(NAME, "reading a file", "GET",
                        q(repo(r) + "/blobs/" + GitHttp.encode(id) + "?$format=octetstream"), octet, null, null).body());
            }
        }
        return out;
    }

    @Override
    public String commit(Remote r, String branch, String parent, Map<String, byte[]> upserts, Set<String> deletes,
            Set<String> existing, String message) {
        ObjectNode body = GitHttp.JSON.createObjectNode();
        body.putArray("refUpdates").addObject().put("name", "refs/heads/" + branch).put("oldObjectId", parent);
        ObjectNode commit = body.putArray("commits").addObject().put("comment", message);
        ArrayNode changes = commit.putArray("changes");
        upserts.forEach((path, bytes) -> {
            ObjectNode change = changes.addObject().put("changeType", existing.contains(path) ? "edit" : "add");
            change.putObject("item").put("path", "/" + path);
            change.putObject("newContent").put("content", Base64.getEncoder().encodeToString(bytes))
                    .put("contentType", "base64encoded");
        });
        deletes.forEach(path -> changes.addObject().put("changeType", "delete").putObject("item").put("path", "/" + path));
        GitHttp.Response response = r.http().send(NAME, "pushing the commit", "POST", q(repo(r) + "/pushes"), headers(r),
                GitHttp.JSON.writeValueAsBytes(body), "application/json");
        if (response.status() == 409) {
            throw new GitException(NAME + " refused the push: the branch changed since it was read.", 409);
        }
        if (!response.ok()) {
            throw new GitException(NAME + " pushing the commit failed with HTTP " + response.status() + ".", response.status());
        }
        JsonNode pushed = response.json(NAME);
        return pushed.path("commits").path(0).path("commitId").asString("");
    }

    @Override
    public Optional<PullRequest> findOpenPullRequest(Remote r, String branch, String base) {
        JsonNode list = r.http().json(NAME, "listing pull requests", "GET", q(repo(r)
                + "/pullrequests?searchCriteria.status=active&searchCriteria.sourceRefName="
                + GitHttp.encode("refs/heads/" + branch) + "&searchCriteria.targetRefName="
                + GitHttp.encode("refs/heads/" + base)), headers(r), null);
        for (JsonNode pr : list.path("value")) {
            return Optional.of(pullRequest(r, pr.path("pullRequestId").asString("")));
        }
        return Optional.empty();
    }

    @Override
    public PullRequest openPullRequest(Remote r, String branch, String base, String title, String description) {
        ObjectNode body = GitHttp.JSON.createObjectNode().put("sourceRefName", "refs/heads/" + branch)
                .put("targetRefName", "refs/heads/" + base).put("title", title).put("description", description);
        JsonNode pr = r.http().json(NAME, "opening the pull request", "POST", q(repo(r) + "/pullrequests"), headers(r), body);
        return pullRequest(r, pr.path("pullRequestId").asString(""));
    }

    @Override
    public PullRequest pullRequest(Remote r, String id) {
        JsonNode pr = r.http().json(NAME, "reading the pull request", "GET",
                q(repo(r) + "/pullrequests/" + GitHttp.encode(id)), headers(r), null);
        String state = switch (pr.path("status").asString("")) {
            case "completed" -> "merged";
            case "active" -> "open";
            default -> "closed";
        };
        List<String> approvers = new ArrayList<>();
        for (JsonNode reviewer : pr.path("reviewers")) {
            if (reviewer.path("vote").asInt(0) >= 5) {
                approvers.add(reviewer.path("uniqueName").asString(reviewer.path("displayName").asString("")));
            }
        }
        String[] p = parts(r);
        String url = GitHttp.trimSlash(r.apiUrl()) + "/" + GitHttp.encode(p[0]) + "/" + GitHttp.encode(p[1]) + "/_git/"
                + GitHttp.encode(p[2]) + "/pullrequest/" + GitHttp.encode(id);
        return new PullRequest(pr.path("pullRequestId").asString(id), url, state,
                pr.path("sourceRefName").asString("").replaceFirst("^refs/heads/", ""),
                pr.path("lastMergeSourceCommit").path("commitId").asString(""),
                "merged".equals(state) ? pr.path("lastMergeCommit").path("commitId").asString(null) : null,
                List.copyOf(approvers));
    }
}
