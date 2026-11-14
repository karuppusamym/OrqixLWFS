package com.orqix.catalog.git;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Provider SPI for Git sync (CG-S01, docs/build/S147). One implementation per hosting service; each speaks only that
 * service's REST API over {@link GitHttp}. Paths are repository-relative with forward slashes and no leading slash.
 * Implementations never log or return a token or a response body (it could echo content); failures are
 * {@link GitException} with a safe message.
 */
public interface GitProvider {

    /** {@code github}, {@code gitlab}, {@code azure-devops} or {@code bitbucket}. */
    String kind();

    /** The public API root used when the connection names none. */
    String defaultApiUrl();

    /** Repository shape accepted by this provider, for a validation message. */
    String repositoryShape();

    /** @return true when {@code repository} has the shape this provider needs (owner/repo, org/project/repo, ...) */
    boolean acceptsRepository(String repository);

    /** The repository's default branch. */
    String defaultBranch(Remote remote);

    /** @return the commit at the tip of {@code branch}, empty when the branch does not exist */
    Optional<String> branchHead(Remote remote, String branch);

    /** Creates {@code branch} at {@code fromCommit}. */
    void createBranch(Remote remote, String branch, String fromCommit);

    /** Paths of the files (not folders) under {@code folder} at {@code commit}; empty when the folder is absent. */
    Set<String> paths(Remote remote, String commit, String folder);

    /** Contents of the named files at {@code commit}. */
    Map<String, byte[]> read(Remote remote, String commit, Set<String> paths);

    /**
     * Commits on top of {@code parent} and moves {@code branch} to the new commit; refuses (409) when the branch no
     * longer points at {@code parent}. {@code existing} lists the paths present at {@code parent} under the folder.
     *
     * @return the new commit id
     */
    String commit(Remote remote, String branch, String parent, Map<String, byte[]> upserts, Set<String> deletes,
            Set<String> existing, String message);

    /** @return the open pull request from {@code branch} into {@code base}, if any */
    Optional<PullRequest> findOpenPullRequest(Remote remote, String branch, String base);

    PullRequest openPullRequest(Remote remote, String branch, String base, String title, String description);

    PullRequest pullRequest(Remote remote, String id);

    /** Where to call: API root, repository and the resolved token (never stored). */
    record Remote(String apiUrl, String repository, String token, GitHttp http) {
        @Override
        public String toString() {
            return "Remote[" + apiUrl + ", " + repository + "]";
        }
    }

    /**
     * A pull request (GitHub, Bitbucket, Azure DevOps) or merge request (GitLab). {@code state} is open, merged or
     * closed. {@code approvedBy} lists the reviewers whose approval applies to {@code headCommit}. Bitbucket reports
     * abbreviated commit ids; {@link #sameCommit} compares by prefix of at least 12 characters.
     */
    record PullRequest(String id, String url, String state, String sourceBranch, String headCommit, String mergeCommit,
            List<String> approvedBy) {

        public static boolean sameCommit(String reported, String commit) {
            if (reported == null || commit == null) {
                return false;
            }
            return reported.equals(commit) || (reported.length() >= 12 && commit.startsWith(reported));
        }

        public boolean isHead(String commit) {
            return sameCommit(headCommit, commit);
        }

        public boolean isMerge(String commit) {
            return sameCommit(mergeCommit, commit);
        }
    }
}
