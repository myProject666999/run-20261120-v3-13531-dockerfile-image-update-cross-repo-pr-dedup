package com.salesforce.dockerfileimageupdate.reconcile;

/** What the reconciler did for one (repository, image) group. */
public enum ReconcileAction {
    /** Nothing to change: every candidate file already uses the target tag. */
    ALREADY_UP_TO_DATE,
    /** An existing branch already carries exactly the target content; nothing was written. */
    BRANCH_ALREADY_CURRENT,
    /** A new branch and request were created. */
    CREATED,
    /** An existing branch was advanced/refreshed and its request updated. */
    UPDATED,
    /** A branch from an earlier failed run existed; the missing step (commit or request) was completed. */
    RESUMED_INCOMPLETE_WORK,
    /** The branch's request was merged, so the branch is expired and was not reused. */
    EXPIRED_AFTER_MERGE,
    /** The branch's request was closed without merge for the same target tag; the decision is respected. */
    SKIPPED_CLOSED_SAME_TAG,
    /** The branch's request was closed earlier, but a different/newer tag is now requested. */
    RESUBMITTED_AFTER_CLOSE,
    /** The branch has commits by someone other than this tool and cannot be advanced without conflict. */
    LEFT_UNTOUCHED_FOREIGN_COMMITS,
    /** The group failed; other groups/repositories are unaffected. */
    FAILED
}
