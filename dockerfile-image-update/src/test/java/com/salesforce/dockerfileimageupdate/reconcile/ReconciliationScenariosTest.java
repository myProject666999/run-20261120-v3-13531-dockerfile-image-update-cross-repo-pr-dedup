package com.salesforce.dockerfileimageupdate.reconcile;

import com.salesforce.dockerfileimageupdate.reconcile.platform.InMemoryCodeHost;
import com.salesforce.dockerfileimageupdate.reconcile.platform.InMemoryCodeHost.Write;
import com.salesforce.dockerfileimageupdate.reconcile.platform.PlatformException;
import com.salesforce.dockerfileimageupdate.reconcile.platform.PullRequestState;
import org.testng.annotations.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;

/**
 * End-to-end scenarios against the in-memory code host. Each scenario asserts the final number of
 * branches and requests, the tags recorded in request bodies/titles, and the write-call counts.
 */
public class ReconciliationScenariosTest {
    private static final String REPO = "acme/service";
    private static final String OTHER_REPO = "acme/other-service";
    private static final String NGINX = "docker.io/library/nginx";
    private static final String POSTGRES = "docker.io/library/postgres";

    private static Map<String, String> files(String... pathAndContent) {
        Map<String, String> map = new LinkedHashMap<>();
        for (int i = 0; i < pathAndContent.length; i += 2) {
            map.put(pathAndContent[i], pathAndContent[i + 1]);
        }
        return map;
    }

    private static ImageUpdate nginxTo(String tag) {
        return new ImageUpdate("nginx", tag);
    }

    private static List<ImageReference> refs(String repo, String... paths) {
        List<ImageReference> references = new ArrayList<>();
        for (String path : paths) {
            references.add(new ImageReference(repo, path));
        }
        return references;
    }

    private static ReconcileResult single(List<ReconcileResult> results) {
        assertEquals(results.size(), 1, "expected exactly one plan: " + results);
        return results.get(0);
    }

    // ------------------------------------------------------------------ first run

    @Test
    public void firstRunCreatesOneBranchAndOneRequest() {
        InMemoryCodeHost host = new InMemoryCodeHost()
                .addRepo(REPO, files("Dockerfile", "FROM nginx:1.25\nRUN true\n"));
        List<ReconcileResult> results = new ImageUpdateReconciler(host, 4)
                .reconcileAll(nginxTo("1.27"), refs(REPO, "Dockerfile"));

        ReconcileResult result = single(results);
        assertEquals(result.getAction(), ReconcileAction.CREATED);
        assertEquals(result.getBranchName(), "dfiu/" + NGINX);
        assertEquals(host.branchCount(REPO), 1);
        assertEquals(host.pullRequestCount(REPO), 1);
        assertEquals(host.fileOn(REPO, result.getBranchName(), "Dockerfile"), "FROM nginx:1.27\nRUN true\n");
        PullRequestState pr = host.pullRequests(REPO).get(0);
        assertTrue(pr.getTitle().contains(NGINX) && pr.getTitle().endsWith("1.27"), pr.getTitle());
        assertTrue(pr.getBody().contains("`1.25`"), "body must state the old tag: " + pr.getBody());
        assertTrue(pr.getBody().contains("`" + NGINX + ":1.27`"), "body must state the new tag");
        assertTrue(pr.getBody().contains("`Dockerfile`"), "body must list the changed file");
        assertEquals(host.writeCount(Write.CREATE_BRANCH), 1);
        assertEquals(host.writeCount(Write.RESET_BRANCH), 1);
        assertEquals(host.writeCount(Write.CREATE_PULL_REQUEST), 1);
    }

    // ------------------------------------------------------------------ identical rerun

    @Test
    public void identicalRerunMakesNoWrites() {
        InMemoryCodeHost host = new InMemoryCodeHost()
                .addRepo(REPO, files("Dockerfile", "FROM nginx:1.25\n"));
        ImageUpdateReconciler reconciler = new ImageUpdateReconciler(host, 4);
        reconciler.reconcileAll(nginxTo("1.27"), refs(REPO, "Dockerfile"));
        int writesAfterFirstRun = host.writes();

        host.resetCounters();
        List<ReconcileResult> second = reconciler.reconcileAll(nginxTo("1.27"), refs(REPO, "Dockerfile"));

        assertEquals(single(second).getAction(), ReconcileAction.BRANCH_ALREADY_CURRENT);
        assertEquals(host.writes(), 0, "an unchanged rerun must perform no writes");
        assertEquals(writesAfterFirstRun, 3, "create branch, commit content, create request");
        assertEquals(host.branchCount(REPO), 1);
        assertEquals(host.pullRequestCount(REPO), 1);
    }

    // ------------------------------------------------------------------ newer tag

    @Test
    public void newerTagUpdatesExistingBranchAndRequestInPlace() {
        InMemoryCodeHost host = new InMemoryCodeHost()
                .addRepo(REPO, files("Dockerfile", "FROM nginx:1.25\n"));
        ImageUpdateReconciler reconciler = new ImageUpdateReconciler(host, 4);
        reconciler.reconcileAll(nginxTo("1.27"), refs(REPO, "Dockerfile"));
        long requestNumber = host.pullRequests(REPO).get(0).getNumber();

        List<ReconcileResult> second = reconciler.reconcileAll(nginxTo("1.28"), refs(REPO, "Dockerfile"));

        ReconcileResult result = single(second);
        assertEquals(result.getAction(), ReconcileAction.UPDATED);
        assertEquals(host.branchCount(REPO), 1, "no new branch");
        assertEquals(host.pullRequestCount(REPO), 1, "no new request");
        assertEquals(host.pullRequests(REPO).get(0).getNumber(), requestNumber);
        assertEquals(host.fileOn(REPO, result.getBranchName(), "Dockerfile"), "FROM nginx:1.28\n");
        assertEquals(host.toolCommitCount(REPO, result.getBranchName()), 2, "one commit per tag");
        PullRequestState pr = host.pullRequests(REPO).get(0);
        assertTrue(pr.getTitle().endsWith("1.28"), pr.getTitle());
        assertTrue(pr.getBody().contains(NGINX + ":1.28`"), pr.getBody());
        assertTrue(pr.getBody().contains("`1.25`"), "old tag still recorded: " + pr.getBody());
        assertEquals(occurrences(pr.getBody(), "## Summary"), 1, "body must not accumulate sections");
        assertEquals(occurrences(pr.getBody(), "## Files changed"), 1);
        assertEquals(host.writeCount(Write.CREATE_PULL_REQUEST), 1, "only the first run created a request");
        assertEquals(host.writeCount(Write.UPDATE_PULL_REQUEST), 1);
    }

    @Test
    public void rereunningWithNewerTagTwiceIsStable() {
        InMemoryCodeHost host = new InMemoryCodeHost().addRepo(REPO, files("Dockerfile", "FROM nginx:1.25\n"));
        ImageUpdateReconciler reconciler = new ImageUpdateReconciler(host, 4);
        reconciler.reconcileAll(nginxTo("1.28"), refs(REPO, "Dockerfile"));
        host.resetCounters();
        reconciler.reconcileAll(nginxTo("1.28"), refs(REPO, "Dockerfile"));
        assertEquals(host.writes(), 0);
        assertEquals(host.branchCount(REPO), 1);
    }

    // ------------------------------------------------------------------ closed

    @Test
    public void closedRequestForSameTagIsNotResubmitted() throws PlatformException {
        InMemoryCodeHost host = new InMemoryCodeHost().addRepo(REPO, files("Dockerfile", "FROM nginx:1.25\n"));
        ImageUpdateReconciler reconciler = new ImageUpdateReconciler(host, 4);
        reconciler.reconcileAll(nginxTo("1.27"), refs(REPO, "Dockerfile"));
        long closed = host.pullRequests(REPO).get(0).getNumber();
        host.closePullRequest(REPO, closed);

        host.resetCounters();
        List<ReconcileResult> results = reconciler.reconcileAll(nginxTo("1.27"), refs(REPO, "Dockerfile"));

        assertEquals(single(results).getAction(), ReconcileAction.SKIPPED_CLOSED_SAME_TAG);
        assertEquals(host.pullRequestCount(REPO), 1);
        assertEquals(host.writes(), 0, "a respected close requires no writes");
    }

    @Test
    public void closedRequestIsReopenedForANewerTag() throws PlatformException {
        InMemoryCodeHost host = new InMemoryCodeHost().addRepo(REPO, files("Dockerfile", "FROM nginx:1.25\n"));
        ImageUpdateReconciler reconciler = new ImageUpdateReconciler(host, 4);
        reconciler.reconcileAll(nginxTo("1.27"), refs(REPO, "Dockerfile"));
        long closed = host.pullRequests(REPO).get(0).getNumber();
        host.closePullRequest(REPO, closed);

        List<ReconcileResult> results = reconciler.reconcileAll(nginxTo("1.28"), refs(REPO, "Dockerfile"));

        assertEquals(single(results).getAction(), ReconcileAction.RESUBMITTED_AFTER_CLOSE);
        assertEquals(host.pullRequestCount(REPO), 2, "one closed, one new");
        assertEquals(host.pullRequestStatus(REPO, closed), PullRequestState.Status.CLOSED);
        PullRequestState newest = host.pullRequests(REPO).get(1);
        assertEquals(newest.getStatus(), PullRequestState.Status.OPEN);
        assertTrue(newest.getTitle().endsWith("1.28"), newest.getTitle());
        assertTrue(newest.getBody().contains("1.28"));
        assertTrue(newest.getBody().contains("#" + closed), "new body must reference the closed request");
        assertEquals(host.fileOn(REPO, "dfiu/" + NGINX, "Dockerfile"), "FROM nginx:1.28\n");
    }

    // ------------------------------------------------------------------ merged

    @Test
    public void mergedRequestExpiresTheBranchAndRerunIsSilent() throws PlatformException {
        InMemoryCodeHost host = new InMemoryCodeHost().addRepo(REPO, files("Dockerfile", "FROM nginx:1.25\n"));
        ImageUpdateReconciler reconciler = new ImageUpdateReconciler(host, 4);
        List<ReconcileResult> first = reconciler.reconcileAll(nginxTo("1.27"), refs(REPO, "Dockerfile"));
        String branch = single(first).getBranchName();
        host.mergePullRequest(REPO, host.pullRequests(REPO).get(0).getNumber());

        host.resetCounters();
        List<ReconcileResult> second = reconciler.reconcileAll(nginxTo("1.27"), refs(REPO, "Dockerfile"));

        ReconcileResult result = single(second);
        assertEquals(result.getAction(), ReconcileAction.EXPIRED_AFTER_MERGE);
        assertEquals(host.branchCount(REPO), 0, "tool-only merged branch is cleaned up");
        assertFalse(host.hasBranch(REPO, branch));
        assertEquals(host.pullRequestCount(REPO), 1);
        assertEquals(host.writes(), 1, "only the branch deletion");
        assertEquals(host.writeCount(Write.DELETE_BRANCH), 1);
    }

    @Test
    public void mergedRequestDoesNotCauseADuplicateRequestOnRerun() throws PlatformException {
        InMemoryCodeHost host = new InMemoryCodeHost().addRepo(REPO, files("Dockerfile", "FROM nginx:1.25\n"));
        ImageUpdateReconciler reconciler = new ImageUpdateReconciler(host, 4);
        reconciler.reconcileAll(nginxTo("1.27"), refs(REPO, "Dockerfile"));
        host.mergePullRequest(REPO, host.pullRequests(REPO).get(0).getNumber());
        reconciler.reconcileAll(nginxTo("1.27"), refs(REPO, "Dockerfile"));

        host.resetCounters();
        List<ReconcileResult> third = reconciler.reconcileAll(nginxTo("1.27"), refs(REPO, "Dockerfile"));
        assertEquals(single(third).getAction(), ReconcileAction.ALREADY_UP_TO_DATE);
        assertEquals(host.writeCount(Write.CREATE_PULL_REQUEST), 0);
        assertEquals(host.pullRequestCount(REPO), 1);
    }

    @Test
    public void mergedBranchWithForeignCommitsIsReportedNotDeleted() throws PlatformException {
        InMemoryCodeHost host = new InMemoryCodeHost().addRepo(REPO, files("Dockerfile", "FROM nginx:1.25\n"));
        ImageUpdateReconciler reconciler = new ImageUpdateReconciler(host, 4);
        String branch = single(reconciler.reconcileAll(nginxTo("1.27"), refs(REPO, "Dockerfile"))).getBranchName();
        host.mergePullRequest(REPO, host.pullRequests(REPO).get(0).getNumber());
        // Simulate a human adding a follow-up commit on the branch after the merge.
        host.addForeignCommit(REPO, branch, files("README", "human notes"));

        ReconcileResult result = single(reconciler.reconcileAll(nginxTo("1.27"), refs(REPO, "Dockerfile")));
        assertEquals(result.getAction(), ReconcileAction.EXPIRED_AFTER_MERGE);
        assertTrue(host.hasBranch(REPO, branch), "foreign commits must never be deleted");
    }

    // ------------------------------------------------------------------ no changes

    @Test
    public void repositoryWithNothingToChangeGetsNoBranchAndNoCommit() {
        InMemoryCodeHost host = new InMemoryCodeHost()
                .addRepo(REPO, files("Dockerfile", "FROM nginx:1.27\n"));
        List<ReconcileResult> results = new ImageUpdateReconciler(host, 4)
                .reconcileAll(nginxTo("1.27"), refs(REPO, "Dockerfile"));

        assertEquals(single(results).getAction(), ReconcileAction.ALREADY_UP_TO_DATE);
        assertEquals(host.writes(), 0);
        assertEquals(host.branchCount(REPO), 0);
        assertEquals(host.pullRequestCount(REPO), 0);
    }

    @Test
    public void missingCandidateFileIsIgnored() {
        InMemoryCodeHost host = new InMemoryCodeHost().addRepo(REPO, files("Dockerfile", "FROM nginx:1.27\n"));
        List<ReconcileResult> results = new ImageUpdateReconciler(host, 4)
                .reconcileAll(nginxTo("1.27"), refs(REPO, "Dockerfile", "gone/Dockerfile"));
        assertEquals(single(results).getAction(), ReconcileAction.ALREADY_UP_TO_DATE);
        assertEquals(host.writes(), 0);
    }

    // ------------------------------------------------------------------ grouping

    @Test
    public void severalFilesWithSameImageInOneRepoBecomeOneBranchAndOneRequest() {
        InMemoryCodeHost host = new InMemoryCodeHost().addRepo(REPO, files(
                "Dockerfile", "FROM nginx:1.25\n",
                "ci/Dockerfile", "FROM docker.io/library/nginx:1.24\n",
                "docker-compose.yml", "services:\n  web:\n    image: nginx:1.25\n"));
        List<ReconcileResult> results = new ImageUpdateReconciler(host, 4)
                .reconcileAll(nginxTo("1.27"), refs(REPO, "Dockerfile", "ci/Dockerfile", "docker-compose.yml"));

        ReconcileResult result = single(results);
        assertEquals(result.getAction(), ReconcileAction.CREATED);
        assertEquals(host.branchCount(REPO), 1);
        assertEquals(host.pullRequestCount(REPO), 1);
        assertEquals(host.writeCount(Write.CREATE_BRANCH), 1);
        assertEquals(result.getFiles().size(), 3);
        String body = host.pullRequests(REPO).get(0).getBody();
        assertTrue(body.contains("`ci/Dockerfile`") && body.contains("`docker-compose.yml`"), body);
        assertTrue(body.contains("`1.24`") && body.contains("`1.25`"), "all old tags listed: " + body);
    }

    @Test
    public void twoDifferentImagesInOneRepoBecomeTwoRequestsWhenFilesDiffer() {
        InMemoryCodeHost host = new InMemoryCodeHost().addRepo(REPO, files(
                "web/Dockerfile", "FROM nginx:1.25\n",
                "db/Dockerfile", "FROM postgres:15\n"));
        List<ReconcileResult> results = new ImageUpdateReconciler(host, 4).reconcileAll(
                Arrays.asList(nginxTo("1.27"), new ImageUpdate("postgres", "16")),
                Arrays.asList(
                        new ImageReference(REPO, "web/Dockerfile", "nginx:1.25"),
                        new ImageReference(REPO, "db/Dockerfile", "postgres:15")));

        assertEquals(results.size(), 2);
        assertEquals(host.branchCount(REPO), 2);
        assertEquals(host.pullRequestCount(REPO), 2, "independent images must not block each other");
        assertEquals(host.fileOn(REPO, "dfiu/" + NGINX, "web/Dockerfile"), "FROM nginx:1.27\n");
        assertEquals(host.fileOn(REPO, "dfiu/" + POSTGRES, "db/Dockerfile"), "FROM postgres:16\n");
    }

    @Test
    public void twoImagesSharingAFileAreCombinedIntoOneRequest() {
        InMemoryCodeHost host = new InMemoryCodeHost().addRepo(REPO, files(
                "Dockerfile", "FROM nginx:1.25\nFROM postgres:15\n"));
        List<ReconcileResult> results = new ImageUpdateReconciler(host, 4).reconcileAll(
                Arrays.asList(nginxTo("1.27"), new ImageUpdate("postgres", "16")),
                Arrays.asList(
                        new ImageReference(REPO, "Dockerfile", "nginx:1.25"),
                        new ImageReference(REPO, "Dockerfile", "postgres:15")));

        assertEquals(results.size(), 1, "one request for the shared file, so the two changes cannot conflict");
        assertEquals(host.branchCount(REPO), 1);
        assertEquals(host.pullRequestCount(REPO), 1);
        assertEquals(host.fileOn(REPO, results.get(0).getBranchName(), "Dockerfile"),
                "FROM nginx:1.27\nFROM postgres:16\n");
        String body = host.pullRequests(REPO).get(0).getBody();
        assertTrue(body.contains(NGINX) && body.contains(POSTGRES), body);
        assertTrue(body.contains("1.27") && body.contains("16"), body);
    }

    // ------------------------------------------------------------------ default branch rename

    @Test
    public void renamedDefaultBranchDoesNotFailTheRun() {
        InMemoryCodeHost host = new InMemoryCodeHost()
                .addRepo(REPO, "trunk", files("Dockerfile", "FROM nginx:1.25\n"));
        List<ReconcileResult> results = new ImageUpdateReconciler(host, 2)
                .reconcileAll(nginxTo("1.27"), refs(REPO, "Dockerfile"));
        assertEquals(single(results).getAction(), ReconcileAction.CREATED);
        assertEquals(host.fileOn(REPO, "dfiu/" + NGINX, "Dockerfile"), "FROM nginx:1.27\n");
    }

    @Test
    public void defaultBranchRenamedBetweenRunsStillWorks() {
        InMemoryCodeHost host = new InMemoryCodeHost().addRepo(REPO, "master", files("Dockerfile", "FROM nginx:1.25\n"));
        ImageUpdateReconciler reconciler = new ImageUpdateReconciler(host, 2);
        reconciler.reconcileAll(nginxTo("1.27"), refs(REPO, "Dockerfile"));
        host.renameDefaultBranch(REPO, "main");
        ReconcileResult result = single(reconciler.reconcileAll(nginxTo("1.28"), refs(REPO, "Dockerfile")));
        assertEquals(result.getAction(), ReconcileAction.UPDATED);
        assertEquals(host.pullRequestCount(REPO), 1);
    }

    // ------------------------------------------------------------------ branch drift

    @Test
    public void toolOnlyBranchBehindDefaultIsRebuiltNotDuplicated() {
        InMemoryCodeHost host = new InMemoryCodeHost().addRepo(REPO, files(
                "Dockerfile", "FROM nginx:1.25\n",
                "README", "v1\n"));
        ImageUpdateReconciler reconciler = new ImageUpdateReconciler(host, 2);
        String branch = single(reconciler.reconcileAll(nginxTo("1.27"), refs(REPO, "Dockerfile"))).getBranchName();

        host.commitToDefault(REPO, files("README", "v2\n"));
        host.resetCounters();
        ReconcileResult result = single(reconciler.reconcileAll(nginxTo("1.28"), refs(REPO, "Dockerfile")));

        assertEquals(result.getAction(), ReconcileAction.UPDATED);
        assertEquals(host.branchCount(REPO), 1, "the branch is rebuilt in place");
        assertEquals(host.pullRequestCount(REPO), 1);
        assertEquals(host.writeCount(Write.RESET_BRANCH), 1);
        assertEquals(host.writeCount(Write.COMMIT_TO_BRANCH), 0, "a drifted branch is reset, not appended to");
        assertEquals(host.fileOn(REPO, branch, "README"), "v2\n", "rebuild picks up upstream changes");
        assertEquals(host.fileOn(REPO, branch, "Dockerfile"), "FROM nginx:1.28\n");
    }

    @Test
    public void branchWithForeignCommitsIsLeftUntouchedAndReported() throws PlatformException {
        InMemoryCodeHost host = new InMemoryCodeHost().addRepo(REPO, files("Dockerfile", "FROM nginx:1.25\n"));
        ImageUpdateReconciler reconciler = new ImageUpdateReconciler(host, 2);
        String branch = single(reconciler.reconcileAll(nginxTo("1.27"), refs(REPO, "Dockerfile"))).getBranchName();
        host.addForeignCommit(REPO, branch, files("Dockerfile", "FROM nginx:1.27\nRUN human-edit\n"));
        long requests = host.pullRequestCount(REPO);

        host.resetCounters();
        ReconcileResult result = single(reconciler.reconcileAll(nginxTo("1.28"), refs(REPO, "Dockerfile")));

        assertEquals(result.getAction(), ReconcileAction.LEFT_UNTOUCHED_FOREIGN_COMMITS);
        assertTrue(result.getNote().contains("not authored by this tool"), result.getNote());
        assertEquals(host.fileOn(REPO, branch, "Dockerfile"), "FROM nginx:1.27\nRUN human-edit\n",
                "manual edits must survive");
        assertEquals(host.pullRequestCount(REPO), requests);
        assertEquals(host.writes(), 0);
    }

    // ------------------------------------------------------------------ partial work

    @Test
    public void branchCreatedButCommitFailedIsResumedOnNextRun() {
        InMemoryCodeHost host = new InMemoryCodeHost().addRepo(REPO, files("Dockerfile", "FROM nginx:1.25\n"));
        host.failOn(Write.RESET_BRANCH, REPO, 1, "simulated commit failure");
        ImageUpdateReconciler reconciler = new ImageUpdateReconciler(host, 2);

        ReconcileResult first = single(reconciler.reconcileAll(nginxTo("1.27"), refs(REPO, "Dockerfile")));
        assertEquals(first.getAction(), ReconcileAction.FAILED);
        assertEquals(host.branchCount(REPO), 1, "the orphan branch exists after the partial failure");
        assertEquals(host.pullRequestCount(REPO), 0);

        ReconcileResult second = single(reconciler.reconcileAll(nginxTo("1.27"), refs(REPO, "Dockerfile")));
        assertEquals(second.getAction(), ReconcileAction.RESUMED_INCOMPLETE_WORK);
        assertEquals(host.branchCount(REPO), 1, "no second branch was created");
        assertEquals(host.pullRequestCount(REPO), 1);
        assertEquals(host.fileOn(REPO, second.getBranchName(), "Dockerfile"), "FROM nginx:1.27\n");
    }

    @Test
    public void commitSucceededButRequestCreationFailedIsResumedWithoutOrphan() {
        InMemoryCodeHost host = new InMemoryCodeHost().addRepo(REPO, files("Dockerfile", "FROM nginx:1.25\n"));
        host.failOn(Write.CREATE_PULL_REQUEST, REPO, 1, "simulated request failure");
        ImageUpdateReconciler reconciler = new ImageUpdateReconciler(host, 2);

        assertEquals(single(reconciler.reconcileAll(nginxTo("1.27"), refs(REPO, "Dockerfile"))).getAction(),
                ReconcileAction.FAILED);
        assertEquals(host.branchCount(REPO), 1);
        assertEquals(host.pullRequestCount(REPO), 0);

        host.resetCounters();
        ReconcileResult second = single(reconciler.reconcileAll(nginxTo("1.27"), refs(REPO, "Dockerfile")));

        assertEquals(second.getAction(), ReconcileAction.RESUMED_INCOMPLETE_WORK);
        assertEquals(host.branchCount(REPO), 1);
        assertEquals(host.pullRequestCount(REPO), 1);
        assertEquals(host.writeCount(Write.CREATE_BRANCH), 0, "the branch already existed");
        assertEquals(host.writeCount(Write.COMMIT_TO_BRANCH), 0, "the content was already committed");
        assertEquals(host.writeCount(Write.CREATE_PULL_REQUEST), 1, "only the missing request was created");
    }

    @Test
    public void staleBranchWithNothingToChangeIsSkippedOrCleanedUpButNeverReopened() throws PlatformException {
        InMemoryCodeHost host = new InMemoryCodeHost().addRepo(REPO, files("Dockerfile", "FROM nginx:1.25\n"));
        ImageUpdateReconciler reconciler = new ImageUpdateReconciler(host, 2);
        reconciler.reconcileAll(nginxTo("1.27"), refs(REPO, "Dockerfile"));
        host.mergePullRequest(REPO, host.pullRequests(REPO).get(0).getNumber());
        // Another image group reuses nothing; asking again for the now-current tag must not open a request.
        List<ReconcileResult> results = reconciler.reconcileAll(nginxTo("1.27"), refs(REPO, "Dockerfile"));
        assertEquals(single(results).getAction(), ReconcileAction.EXPIRED_AFTER_MERGE);
        assertEquals(host.pullRequestCount(REPO), 1);
        assertEquals(host.branchCount(REPO), 0);
    }

    @Test
    public void upstreamAlreadyFixedButClosedRequestExistsDoesNotReopen() throws PlatformException {
        InMemoryCodeHost host = new InMemoryCodeHost().addRepo(REPO, files("Dockerfile", "FROM nginx:1.25\n"));
        ImageUpdateReconciler reconciler = new ImageUpdateReconciler(host, 2);
        reconciler.reconcileAll(nginxTo("1.27"), refs(REPO, "Dockerfile"));
        long number = host.pullRequests(REPO).get(0).getNumber();
        host.closePullRequest(REPO, number);
        // A human updates the default branch independently; the stale branch still exists.
        host.commitToDefault(REPO, files("Dockerfile", "FROM nginx:1.27\n"));

        host.resetCounters();
        ReconcileResult result = single(reconciler.reconcileAll(nginxTo("1.27"), refs(REPO, "Dockerfile")));
        assertEquals(result.getAction(), ReconcileAction.ALREADY_UP_TO_DATE);
        assertEquals(host.pullRequestCount(REPO), 1, "the closed request must stay closed");
        assertEquals(host.writes(), 0);
    }

    // ------------------------------------------------------------------ helpers

    private static int occurrences(String haystack, String needle) {
        int count = 0;
        int index = haystack.indexOf(needle);
        while (index >= 0) {
            count++;
            index = haystack.indexOf(needle, index + needle.length());
        }
        return count;
    }

    @Test
    public void branchesAreRecordedAsToolOnlyWhenCreatedByTheTool() {
        InMemoryCodeHost host = new InMemoryCodeHost().addRepo(REPO, files("Dockerfile", "FROM nginx:1.25\n"));
        String branch = single(new ImageUpdateReconciler(host, 1)
                .reconcileAll(nginxTo("1.27"), refs(REPO, "Dockerfile"))).getBranchName();
        assertEquals(host.foreignCommitCount(REPO, branch), 0);
        assertTrue(host.toolCommitCount(REPO, branch) > 0);
        assertFalse(Collections.emptyList().contains(branch));
    }
}
