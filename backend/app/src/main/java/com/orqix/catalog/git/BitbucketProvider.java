package com.orqix.catalog.git;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

import org.springframework.stereotype.Component;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Bitbucket Cloud REST API 2.0: src (listing, raw files and multipart commits), branches and pull requests with
 * participants. Repository {@code workspace/repository}; the token is a repository or workspace access token (Bearer).
 * Bitbucket reports abbreviated commit ids on pull requests; they are compared by prefix.
 */
@Component
public class BitbucketProvider implements GitProvider {

    private static final String NAME = "Bitbucket";

    @Override
    public String kind() {
        return "bitbucket";
    }

    @Override
    public String defaultApiUrl() {
        return "https://api.bitbucket.org/2.0";
    }

    @Override
    public String repositoryShape() {
        return "workspace/repository";
    }

    @Override
    public boolean acceptsRepository(String repository) {
        return repository.split("/").length == 2 && !repository.contains(" ");
    }

    private static Map<String, String> headers(Remote r) {
        Map<String, String> h = new LinkedHashMap<>();
        h.put("Authorization", "Bearer " + r.token());
        h.put("Accept", "application/json");
        return h;
    }

    private static String repo(Remote r) {
        return GitHttp.trimSlash(r.apiUrl()) + "/repositories/" + GitHttp.encodePath(r.repository());
    }

    @Override
    public String defaultBranch(Remote r) {
        return r.http().json(NAME, "reading the repository", "GET", repo(r), headers(r), null).path("mainbranch")
                .path("name").asString("");
    }

    @Override
    public Optional<String> branchHead(Remote r, String branch) {
        GitHttp.Response response = r.http().send(NAME, "reading the branch", "GET",
                repo(r) + "/refs/branches/" + GitHttp.encode(branch), headers(r), null, null);
        if (response.status() == 404) {
            return Optional.empty();
        }
        if (!response.ok()) {
            throw new GitException(NAME + " reading the branch failed with HTTP " + response.status() + ".", response.status());
        }
        return Optional.of(response.json(NAME).path("target").path("hash").asString(""));
    }

    @Override
    public void createBranch(Remote r, String branch, String fromCommit) {
        ObjectNode body = GitHttp.JSON.createObjectNode().put("name", branch);
        body.putObject("target").put("hash", fromCommit);
        r.http().json(NAME, "creating the branch", "POST", repo(r) + "/refs/branches", headers(r), body);
    }

    @Override
    public Set<String> paths(Remote r, String commit, String folder) {
        Set<String> out = new TreeSet<>();
        String root = repo(r);
        String url = root + "/src/" + GitHttp.encode(commit) + "/" + GitHttp.encodePath(folder) + "/?max_depth=10&pagelen=100";
        for (int guard = 0; guard < 20 && url != null; guard++) {
            GitHttp.Response response = r.http().send(NAME, "reading the tree", "GET", url, headers(r), null, null);
            if (response.status() == 404) {
                return out;
            }
            if (!response.ok()) {
                throw new GitException(NAME + " reading the tree failed with HTTP " + response.status() + ".", response.status());
            }
            JsonNode page = response.json(NAME);
            for (JsonNode item : page.path("values")) {
                if ("commit_file".equals(item.path("type").asString(""))) {
                    out.add(item.path("path").asString(""));
                }
            }
            String next = page.path("next").asString(null);
            // Follow only links on the same API root; anything else could leave the vetted host.
            url = next != null && next.startsWith(root + "/") ? next : null;
        }
        return out;
    }

    @Override
    public Map<String, byte[]> read(Remote r, String commit, Set<String> paths) {
        Map<String, byte[]> out = new LinkedHashMap<>();
        for (String path : paths) {
            GitHttp.Response response = r.http().send(NAME, "reading a file", "GET",
                    repo(r) + "/src/" + GitHttp.encode(commit) + "/" + GitHttp.encodePath(path), headers(r), null, null);
            if (response.status() == 404) {
                continue;
            }
            if (!response.ok()) {
                throw new GitException(NAME + " reading a file failed with HTTP " + response.status() + ".", response.status());
            }
            out.put(path, response.body());
        }
        return out;
    }

    @Override
    public String commit(Remote r, String branch, String parent, Map<String, byte[]> upserts, Set<String> deletes,
            Set<String> existing, String message) {
        String boundary = "orqix-" + UUID.randomUUID();
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        part(body, boundary, "message", message.getBytes(StandardCharsets.UTF_8));
        part(body, boundary, "branch", branch.getBytes(StandardCharsets.UTF_8));
        // parents makes Bitbucket refuse the commit when the branch tip is no longer this commit.
        part(body, boundary, "parents", parent.getBytes(StandardCharsets.UTF_8));
        upserts.forEach((path, bytes) -> part(body, boundary, "/" + path, bytes));
        deletes.forEach(path -> part(body, boundary, "files", ("/" + path).getBytes(StandardCharsets.UTF_8)));
        body.writeBytes(("--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        GitHttp.Response response = r.http().send(NAME, "writing the commit", "POST", repo(r) + "/src", headers(r),
                body.toByteArray(), "multipart/form-data; boundary=" + boundary);
        if (response.status() == 409 || response.status() == 412) {
            throw new GitException(NAME + " refused the commit: the branch changed since it was read.", 409);
        }
        if (!response.ok()) {
            throw new GitException(NAME + " writing the commit failed with HTTP " + response.status() + ".", response.status());
        }
        String location = response.header("Location").orElse("");
        int at = location.lastIndexOf("/commit/");
        if (at >= 0) {
            return location.substring(at + "/commit/".length());
        }
        return branchHead(r, branch).orElseThrow(() -> new GitException(NAME + ": the commit could not be found.", 502));
    }

    private static void part(ByteArrayOutputStream out, String boundary, String name, byte[] value) {
        String header = "--" + boundary + "\r\nContent-Disposition: form-data; name=\"" + name.replace("\"", "%22")
                + "\"\r\n\r\n";
        out.writeBytes(header.getBytes(StandardCharsets.UTF_8));
        out.writeBytes(value);
        out.writeBytes("\r\n".getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public Optional<PullRequest> findOpenPullRequest(Remote r, String branch, String base) {
        String query = "source.branch.name=\"" + branch + "\" AND destination.branch.name=\"" + base + "\" AND state=\"OPEN\"";
        JsonNode list = r.http().json(NAME, "listing pull requests", "GET",
                repo(r) + "/pullrequests?q=" + GitHttp.encode(query), headers(r), null);
        for (JsonNode pr : list.path("values")) {
            return Optional.of(pullRequest(r, pr.path("id").asString("")));
        }
        return Optional.empty();
    }

    @Override
    public PullRequest openPullRequest(Remote r, String branch, String base, String title, String description) {
        ObjectNode body = GitHttp.JSON.createObjectNode().put("title", title).put("description", description);
        body.putObject("source").putObject("branch").put("name", branch);
        body.putObject("destination").putObject("branch").put("name", base);
        JsonNode pr = r.http().json(NAME, "opening the pull request", "POST", repo(r) + "/pullrequests", headers(r), body);
        return pullRequest(r, pr.path("id").asString(""));
    }

    @Override
    public PullRequest pullRequest(Remote r, String id) {
        JsonNode pr = r.http().json(NAME, "reading the pull request", "GET", repo(r) + "/pullrequests/" + GitHttp.encode(id),
                headers(r), null);
        String state = switch (pr.path("state").asString("")) {
            case "MERGED" -> "merged";
            case "OPEN" -> "open";
            default -> "closed";
        };
        List<String> approvers = new ArrayList<>();
        for (JsonNode participant : pr.path("participants")) {
            if (participant.path("approved").asBoolean(false)) {
                approvers.add(participant.path("user").path("nickname").asString(
                        participant.path("user").path("display_name").asString("")));
            }
        }
        return new PullRequest(pr.path("id").asString(id), pr.path("links").path("html").path("href").asString(null),
                state, pr.path("source").path("branch").path("name").asString(""), pr.path("source").path("commit").path("hash").asString(""),
                pr.path("merge_commit").path("hash").asString(null), List.copyOf(approvers));
    }
}
