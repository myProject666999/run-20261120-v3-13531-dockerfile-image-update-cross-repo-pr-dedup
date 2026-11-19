package com.salesforce.dockerfileimageupdate.reconcile.platform;

import java.util.Objects;

/**
 * Snapshot of a branch on the host, relative to its repository's default branch.
 *
 * @see com.salesforce.dockerfileimageupdate.reconcile.BranchReconciler for how the fields drive decisions
 */
public final class BranchState {
    /** Where the branch's head came from, which decides whether rewriting history is safe. */
    public enum Provenance {
        /** Every commit on the branch was authored by this tool. */
        TOOL_ONLY,
        /** At least one commit was authored by someone else. */
        HAS_FOREIGN_COMMITS
    }

    private final String name;
    private final String defaultBranchSha;
    private final String branchSha;
    private final Provenance provenance;
    private final int foreignCommitCount;
    private final boolean fastForwardable;

    public BranchState(String name,
                       String defaultBranchSha,
                       String branchSha,
                       Provenance provenance,
                       int foreignCommitCount,
                       boolean fastForwardable) {
        this.name = Objects.requireNonNull(name, "name");
        this.defaultBranchSha = defaultBranchSha;
        this.branchSha = branchSha;
        this.provenance = Objects.requireNonNull(provenance, "provenance");
        this.foreignCommitCount = foreignCommitCount;
        this.fastForwardable = fastForwardable;
    }

    public String getName() {
        return name;
    }

    public String getDefaultBranchSha() {
        return defaultBranchSha;
    }

    public String getBranchSha() {
        return branchSha;
    }

    public Provenance getProvenance() {
        return provenance;
    }

    public int getForeignCommitCount() {
        return foreignCommitCount;
    }

    /**
     * @return true when the branch contains the current default branch head, so new work can be
     *         appended without a conflict
     */
    public boolean isFastForwardable() {
        return fastForwardable;
    }

    public boolean isToolOnly() {
        return provenance == Provenance.TOOL_ONLY;
    }
}
