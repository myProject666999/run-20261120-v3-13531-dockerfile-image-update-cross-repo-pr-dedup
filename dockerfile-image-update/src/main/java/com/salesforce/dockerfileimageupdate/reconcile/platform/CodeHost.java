package com.salesforce.dockerfileimageupdate.reconcile.platform;

import java.util.List;

/**
 * Minimal code-host operations the reconciler needs.
 *
 * <p>Every method must be safe to call repeatedly. Implementations should surface "already exists"
 * races as {@link BranchAlreadyExistsException} rather than a generic failure, so the reconciler can
 * treat the branch as created by another executor and reuse it.</p>
 */
public interface CodeHost {

    /**
     * @param repo full repository name
     * @return name of the repository's default branch, whatever it is currently called
     */
    String getDefaultBranch(String repo) throws PlatformException;

    /**
     * Read a file from a ref.
     *
     * @return file content, or {@code null} if the path does not exist on that ref
     */
    String readFile(String repo, String ref, String path) throws PlatformException;

    /**
     * @return branch state, or {@code null} if the branch does not exist
     */
    BranchState getBranch(String repo, String branch) throws PlatformException;

    /**
     * Create a branch pointing at the current default-branch head.
     *
     * @throws BranchAlreadyExistsException if the ref already exists
     */
    void createBranch(String repo, String branch) throws PlatformException;

    /**
     * Replace a branch with a commit whose content is exactly {@code files}, based on the current
     * default branch head. Used both to build a fresh branch and to rebuild a tool-only branch
     * that has drifted from the default branch.
     *
     * <p>Runs only if every commit currently on the branch was authored by this tool; the host is
     * expected to reject the reset otherwise.</p>
     */
    void resetBranchToContent(String repo, String branch, List<FileChange> files) throws PlatformException;

    /**
     * Append a commit to an existing branch, updating {@code files} relative to the branch's current
     * content. The commit must be authored by this tool.
     */
    void commitToBranch(String repo, String branch, List<FileChange> files, String message) throws PlatformException;

    /**
     * Open requests for the branch, in any state (open, closed, merged). Returns at most the ones
     * whose head is {@code branch}; callers filter by state.
     */
    List<PullRequestState> findPullRequests(String repo, String branch) throws PlatformException;

    long createPullRequest(String repo, String headBranch, String title, String body) throws PlatformException;

    void updatePullRequest(long number, String title, String body) throws PlatformException;

    /** One file's new full content. */
    final class FileChange {
        private final String path;
        private final String content;

        public FileChange(String path, String content) {
            this.path = path;
            this.content = content;
        }

        public String getPath() {
            return path;
        }

        public String getContent() {
            return content;
        }
    }
}
