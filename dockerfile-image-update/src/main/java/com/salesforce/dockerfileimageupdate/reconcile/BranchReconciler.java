package com.salesforce.dockerfileimageupdate.reconcile;

import com.salesforce.dockerfileimageupdate.reconcile.platform.BranchAlreadyExistsException;
import com.salesforce.dockerfileimageupdate.reconcile.platform.BranchState;
import com.salesforce.dockerfileimageupdate.reconcile.platform.CodeHost;
import com.salesforce.dockerfileimageupdate.reconcile.platform.CodeHost.FileChange;
import com.salesforce.dockerfileimageupdate.reconcile.platform.PlatformException;
import com.salesforce.dockerfileimageupdate.reconcile.platform.PullRequestState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Brings one {@link UpdatePlan} (a repository plus one or more images) to the desired tags,
 * idempotently.
 *
 * <h2>Decision table for an existing branch</h2>
 * <ol>
 *   <li><b>Branch content already equals the target.</b> Never write anything. If an open request
 *       exists it is already correct; if the request was merged the branch is expired; if it was
 *       closed for this same target the close is respected; if no request exists at all the branch
 *       is an orphan from a run that died before creating the request, and the missing request is
 *       created.</li>
 *   <li><b>Branch carries an older tag.</b> Append a commit on the same branch to reach the target,
 *       then regenerate title and body on the existing request. No new request is created.</li>
 *   <li><b>Request closed, not merged.</b> A close is a human decision, so the same target is not
 *       resubmitted ({@link ReconcileAction#SKIPPED_CLOSED_SAME_TAG}). A different, newer tag is
 *       allowed through ({@link ReconcileAction#RESUBMITTED_AFTER_CLOSE}) because it carries
 *       information the maintainers have not rejected.</li>
 *   <li><b>Request merged.</b> The default branch is already updated; the branch is expired and
 *       never reused ({@link ReconcileAction#EXPIRED_AFTER_MERGE}). When it is tool-only it is
 *       deleted, which is the cleanup.</li>
 * </ol>
 *
 * <h2>Branch behind the default branch</h2>
 * <p>The two options are to force-push a rebuild, or to abandon the branch and open a new one.
 * This implementation rebuilds only when every commit on the branch was produced by this tool.
 * If any commit is foreign the branch is left untouched and reported: a force push would erase a
 * colleague's work, and opening a second branch would produce a duplicate request.</p>
 *
 * <h2>Partial failures</h2>
 * <p>The branch is the durable record of progress, so every step can be retried: a branch without a
 * request is resumed, and an existing request is updated rather than duplicated. A branch created
 * concurrently by another executor is reused instead of failing.</p>
 */
public class BranchReconciler {
    private static final Logger log = LoggerFactory.getLogger(BranchReconciler.class);

    private final CodeHost host;

    public BranchReconciler(CodeHost host) {
        this.host = host;
    }

    /**
     * @param plan repository, images/tags and candidate files
     * @return outcome; failures are returned rather than thrown
     */
    public ReconcileResult reconcile(UpdatePlan plan) {
        String branchName = plan.branchName();
        try {
            return doReconcile(plan, branchName);
        } catch (PlatformException e) {
            log.warn("Failed to reconcile {}: {}", branchName, e.getMessage());
            return result(plan, branchName, ReconcileAction.FAILED,
                    Collections.<String>emptyList(), Collections.<String>emptyList(), e.getMessage());
        }
    }

    private ReconcileResult doReconcile(UpdatePlan plan, String branchName) throws PlatformException {
        String repo = plan.getRepository();
        String defaultBranch = host.getDefaultBranch(repo);

        // Compute what every candidate file should look like at the target tags.
        List<DockerfileRewriter> rewriters = new ArrayList<>();
        for (ImageUpdate update : plan.getUpdates()) {
            rewriters.add(new DockerfileRewriter(update.getImage(), update.getTargetTag()));
        }
        Map<String, String> desired = new LinkedHashMap<>();
        Map<String, Set<String>> previousTagsByFile = new LinkedHashMap<>();
        List<String> changedPaths = new ArrayList<>();
        for (String path : plan.getPaths()) {
            String current = host.readFile(repo, defaultBranch, path);
            if (current == null) {
                continue;
            }
            String rewritten = current;
            Set<String> tags = new TreeSet<>();
            for (DockerfileRewriter rewriter : rewriters) {
                DockerfileRewriter.FileRewrite rewrite = rewriter.rewrite(path, rewritten);
                rewritten = rewrite.getContent();
                tags.addAll(rewrite.getPreviousTags());
            }
            desired.put(path, rewritten);
            if (!rewritten.equals(current)) {
                changedPaths.add(path);
                previousTagsByFile.put(path, tags);
            }
        }
        Set<String> previousTags = new TreeSet<>();
        for (Set<String> tags : previousTagsByFile.values()) {
            previousTags.addAll(tags);
        }

        BranchState branch = host.getBranch(repo, branchName);
        if (changedPaths.isEmpty()) {
            // Nothing in the code needs changing. Never create an empty branch, commit or request,
            // even if a leftover branch from an earlier run happens to exist.
            if (branch != null) {
                return cleanupIfMerged(plan, branchName, branch);
            }
            return result(plan, branchName, ReconcileAction.ALREADY_UP_TO_DATE,
                    Collections.<String>emptyList(), Collections.<String>emptyList(),
                    "no file references an older tag of these images");
        }
        if (branch != null && contentEquals(repo, branchName, desired)) {
            return handleExistingContent(plan, branchName, branch);
        }

        List<FileChange> changes = toChanges(desired, changedPaths);
        if (branch == null) {
            return createFresh(plan, branchName, changes, changedPaths, previousTags);
        }
        return updateExisting(plan, branchName, branch, changes, changedPaths, previousTags);
    }

    /**
     * Nothing to change on the default branch. A leftover branch in the same state as an already
     * merged request is expired work, so report (and, if tool-only, delete) it; otherwise report
     * that no work is needed.
     */
    private ReconcileResult cleanupIfMerged(UpdatePlan plan, String branchName, BranchState branch)
            throws PlatformException {
        List<PullRequestState> requests = host.findPullRequests(plan.getRepository(), branchName);
        if (findMerged(requests) != null) {
            return expire(plan, branchName, branch,
                    "default branch already carries the target and the request was merged; "
                            + "branch is expired and was not reused");
        }
        if (findOpen(requests) != null) {
            return result(plan, branchName, ReconcileAction.BRANCH_ALREADY_CURRENT,
                    Collections.<String>emptyList(), Collections.<String>emptyList(),
                    "branch and open request already carry the target");
        }
        return result(plan, branchName, ReconcileAction.ALREADY_UP_TO_DATE,
                Collections.<String>emptyList(), Collections.<String>emptyList(),
                "no file references an older tag of these images");
    }

    /** Branch already has the target content. Reuse the request if present, otherwise finish the run. */
    private ReconcileResult handleExistingContent(UpdatePlan plan, String branchName, BranchState branch)
            throws PlatformException {
        String repo = plan.getRepository();
        List<PullRequestState> requests = host.findPullRequests(repo, branchName);
        if (findOpen(requests) != null) {
            return result(plan, branchName, ReconcileAction.BRANCH_ALREADY_CURRENT,
                    Collections.<String>emptyList(), Collections.<String>emptyList(),
                    "branch and open request already carry the target");
        }
        if (findMerged(requests) != null) {
            return expire(plan, branchName, branch,
                    "request was merged; branch is expired and was not reused");
        }
        if (findClosed(requests) != null) {
            return result(plan, branchName, ReconcileAction.SKIPPED_CLOSED_SAME_TAG,
                    Collections.<String>emptyList(), Collections.<String>emptyList(),
                    "request was closed for this target; respecting the close");
        }
        // The commit exists but request creation never completed in a previous run.
        PullRequestOutcome outcome = openPullRequest(repo, branchName, plan.title(),
                body(plan, branchName, Collections.<String>emptyList(), Collections.<String>emptySet(),
                        "Resumed incomplete work from a previous run."));
        return result(plan, branchName, ReconcileAction.RESUMED_INCOMPLETE_WORK,
                Collections.<String>emptyList(), Collections.<String>emptyList(),
                (outcome.created ? "created" : "reused concurrently created")
                        + " request #" + outcome.number + " for an existing branch");
    }

    private ReconcileResult createFresh(UpdatePlan plan, String branchName, List<FileChange> changes,
                                        List<String> changedPaths, Set<String> previousTags) throws PlatformException {
        String repo = plan.getRepository();
        try {
            host.createBranch(repo, branchName);
        } catch (BranchAlreadyExistsException race) {
            // Another executor created the same deterministic branch between our read and write.
            // Treat it as ours and continue on the "existing branch" path instead of failing.
            log.info("Branch {} in {} was created concurrently; reusing it", branchName, repo);
            BranchState branch = host.getBranch(repo, branchName);
            if (branch == null) {
                throw race;
            }
            return updateExisting(plan, branchName, branch, changes, changedPaths, previousTags);
        }
        host.resetBranchToContent(repo, branchName, changes);
        PullRequestOutcome outcome = openPullRequest(repo, branchName, plan.title(),
                body(plan, branchName, changedPaths, previousTags, "Created by dockerfile-image-update."));
        return result(plan, branchName, ReconcileAction.CREATED, changedPaths,
                new ArrayList<>(previousTags),
                (outcome.created ? "created" : "reused concurrently created")
                        + " request #" + outcome.number);
    }

    private ReconcileResult updateExisting(UpdatePlan plan, String branchName, BranchState branch,
                                           List<FileChange> changes, List<String> changedPaths,
                                           Set<String> previousTags) throws PlatformException {
        String repo = plan.getRepository();
        List<PullRequestState> requests = host.findPullRequests(repo, branchName);
        PullRequestState open = findOpen(requests);
        PullRequestState merged = findMerged(requests);
        PullRequestState closed = findClosed(requests);

        if (open == null && merged != null) {
            return expire(plan, branchName, branch,
                    "request was merged; branch is expired and was not reused");
        }
        if (open == null && closed != null) {
            if (plan.marker().equals(UpdatePlan.markerIn(closed.getBody()))) {
                return result(plan, branchName, ReconcileAction.SKIPPED_CLOSED_SAME_TAG,
                        changedPaths, new ArrayList<>(previousTags),
                        "request #" + closed.getNumber() + " was closed for this target; respecting the close");
            }
            return resubmit(plan, branchName, branch, changes, changedPaths, previousTags, closed);
        }

        // Either an open request needs its content/title/body refreshed, or no request exists because
        // a previous run died between committing and creating the request.
        if (!branch.isToolOnly()) {
            return leftUntouched(plan, branchName, branch);
        }
        if (branch.isFastForwardable()) {
            host.commitToBranch(repo, branchName, changes, commitMessage(plan, changedPaths));
        } else {
            // Tool-only branch has drifted from the default branch: rebuild it on the new head.
            host.resetBranchToContent(repo, branchName, changes);
        }
        String title = plan.title();
        String body = body(plan, branchName, changedPaths, previousTags,
                "Updated by dockerfile-image-update.");
        if (open != null) {
            host.updatePullRequest(open.getNumber(), title, body);
            return result(plan, branchName, ReconcileAction.UPDATED, changedPaths,
                    new ArrayList<>(previousTags), "updated request #" + open.getNumber());
        }
        PullRequestOutcome outcome = openPullRequest(repo, branchName, title, body);
        return result(plan, branchName, ReconcileAction.RESUMED_INCOMPLETE_WORK, changedPaths,
                new ArrayList<>(previousTags),
                (outcome.created ? "completed missing" : "reused concurrently created")
                        + " request #" + outcome.number + " for an existing branch");
    }

    private ReconcileResult resubmit(UpdatePlan plan, String branchName, BranchState branch,
                                     List<FileChange> changes, List<String> changedPaths,
                                     Set<String> previousTags, PullRequestState closed) throws PlatformException {
        String repo = plan.getRepository();
        if (!branch.isToolOnly()) {
            // Someone else's commits sit on the branch; do not build on top of a rejected change.
            return result(plan, branchName, ReconcileAction.LEFT_UNTOUCHED_FOREIGN_COMMITS,
                    changedPaths, new ArrayList<>(previousTags),
                    "previous request #" + closed.getNumber() + " was closed and the branch has foreign commits");
        }
        if (branch.isFastForwardable()) {
            host.commitToBranch(repo, branchName, changes, commitMessage(plan, changedPaths));
        } else {
            host.resetBranchToContent(repo, branchName, changes);
        }
        String body = body(plan, branchName, changedPaths, previousTags,
                "Previous request #" + closed.getNumber() + " for a different target was closed; "
                        + "this request is for " + describeTargets(plan) + ".");
        PullRequestOutcome outcome = openPullRequest(repo, branchName, plan.title(), body);
        return result(plan, branchName, ReconcileAction.RESUBMITTED_AFTER_CLOSE, changedPaths,
                new ArrayList<>(previousTags),
                "request #" + outcome.number + " after close of #" + closed.getNumber());
    }

    /** Merged branch: never reuse. Delete it when tool-only; otherwise just report it as expired. */
    private ReconcileResult expire(UpdatePlan plan, String branchName, BranchState branch, String note)
            throws PlatformException {
        if (branch.isToolOnly() && host instanceof BranchDeleter) {
            ((BranchDeleter) host).deleteBranch(plan.getRepository(), branchName);
            note = note + "; deleted tool-only branch";
        }
        return result(plan, branchName, ReconcileAction.EXPIRED_AFTER_MERGE,
                Collections.<String>emptyList(), Collections.<String>emptyList(), note);
    }

    /**
     * Create a request, tolerating the race where another executor created the same one first. The
     * platform reports that as a failure; the request that already exists is the one we want.
     */
    private PullRequestOutcome openPullRequest(String repo, String branchName, String title, String body)
            throws PlatformException {
        try {
            return new PullRequestOutcome(host.createPullRequest(repo, branchName, title, body), true);
        } catch (PlatformException e) {
            List<PullRequestState> requests = host.findPullRequests(repo, branchName);
            PullRequestState open = findOpen(requests);
            if (open == null) {
                throw e;
            }
            log.info("Request for {} already exists as #{}; reusing it", branchName, open.getNumber());
            return new PullRequestOutcome(open.getNumber(), false);
        }
    }

    private static final class PullRequestOutcome {
        private final long number;
        private final boolean created;

        private PullRequestOutcome(long number, boolean created) {
            this.number = number;
            this.created = created;
        }
    }

    private ReconcileResult leftUntouched(UpdatePlan plan, String branchName, BranchState branch) {
        return result(plan, branchName, ReconcileAction.LEFT_UNTOUCHED_FOREIGN_COMMITS,
                Collections.<String>emptyList(), Collections.<String>emptyList(),
                "branch has " + branch.getForeignCommitCount()
                        + " commit(s) not authored by this tool; left in place and needs review");
    }

    private boolean contentEquals(String repo, String branchName, Map<String, String> desired)
            throws PlatformException {
        for (Map.Entry<String, String> entry : desired.entrySet()) {
            String onBranch = host.readFile(repo, branchName, entry.getKey());
            if (onBranch == null || !onBranch.equals(entry.getValue())) {
                return false;
            }
        }
        return true;
    }

    private static List<FileChange> toChanges(Map<String, String> desired, List<String> changedPaths) {
        List<FileChange> changes = new ArrayList<>();
        for (String path : changedPaths) {
            changes.add(new FileChange(path, desired.get(path)));
        }
        return changes;
    }

    private static PullRequestState findOpen(List<PullRequestState> states) {
        for (PullRequestState state : states) {
            if (state.isOpen()) {
                return state;
            }
        }
        return null;
    }

    private static PullRequestState findMerged(List<PullRequestState> states) {
        for (PullRequestState state : states) {
            if (state.isMerged()) {
                return state;
            }
        }
        return null;
    }

    private static PullRequestState findClosed(List<PullRequestState> states) {
        for (PullRequestState state : states) {
            if (state.isClosedUnmerged()) {
                return state;
            }
        }
        return null;
    }

    private static String commitMessage(UpdatePlan plan, List<String> paths) {
        return "Update " + describeTargets(plan) + "\n\nFiles: " + String.join(", ", paths);
    }

    private static String describeTargets(UpdatePlan plan) {
        StringBuilder builder = new StringBuilder();
        boolean first = true;
        for (ImageUpdate update : plan.getUpdates()) {
            if (!first) {
                builder.append(", ");
            }
            builder.append(update.getImage().normalized()).append(" to ").append(update.getTargetTag());
            first = false;
        }
        return builder.toString();
    }

    /**
     * Body is regenerated wholesale on every update, so reusing a branch never accumulates
     * multiple history sections. It always states the files, the old tags and the new tags, plus a
     * machine-readable marker of the desired end state used to decide whether a closed request
     * should be respected.
     */
    static String body(UpdatePlan plan, String branchName, List<String> files,
                       Set<String> previousTags, String note) {
        StringBuilder builder = new StringBuilder();
        builder.append(plan.marker()).append("\n\n");
        builder.append("## Summary\n\n");
        for (ImageUpdate update : plan.getUpdates()) {
            builder.append("- Image: `").append(update.getImage().normalized()).append("`\n");
        }
        builder.append("- Branch: `").append(branchName).append("`\n");
        builder.append("- Previous tags: ")
                .append(previousTags.isEmpty() ? "_none recorded_" : joinCode(previousTags))
                .append("\n");
        builder.append("- New tags: ").append(newTags(plan)).append("\n\n");
        builder.append("## Files changed\n\n");
        if (files.isEmpty()) {
            builder.append("_No file content differs; see the branch diff._\n");
        } else {
            for (String file : files) {
                builder.append("- `").append(file).append("`\n");
            }
        }
        builder.append("\n").append(note).append("\n");
        return builder.toString();
    }

    private static String newTags(UpdatePlan plan) {
        Set<String> values = new TreeSet<>();
        for (ImageUpdate update : plan.getUpdates()) {
            values.add(update.getImage().normalized() + ":" + update.getTargetTag());
        }
        return joinCode(values);
    }

    private static String joinCode(Set<String> values) {
        StringBuilder builder = new StringBuilder();
        boolean first = true;
        for (String value : new TreeSet<>(values)) {
            if (!first) {
                builder.append(", ");
            }
            builder.append('`').append(value).append('`');
            first = false;
        }
        return builder.toString();
    }

    private static ReconcileResult result(UpdatePlan plan, String branchName, ReconcileAction action,
                                          List<String> files, List<String> previousTags, String note) {
        StringBuilder targets = new StringBuilder();
        for (ImageUpdate update : plan.getUpdates()) {
            if (targets.length() > 0) {
                targets.append(',');
            }
            targets.append(update.getTargetTag());
        }
        return new ReconcileResult(plan.getRepository(), plan.branchImage(), branchName, action,
                files, previousTags, targets.toString(), note);
    }

    /** Optional capability: hosts that can delete branches let a merged branch be cleaned up. */
    public interface BranchDeleter {
        void deleteBranch(String repo, String branch) throws PlatformException;
    }
}
