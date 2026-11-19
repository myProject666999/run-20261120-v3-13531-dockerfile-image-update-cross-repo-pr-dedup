package com.salesforce.dockerfileimageupdate.reconcile;

import com.salesforce.dockerfileimageupdate.reconcile.platform.InMemoryCodeHost;
import com.salesforce.dockerfileimageupdate.reconcile.platform.InMemoryCodeHost.Write;
import org.testng.annotations.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;

public class ConcurrencyScenariosTest {

    private static Map<String, String> files(String path, String content) {
        Map<String, String> map = new LinkedHashMap<>();
        map.put(path, content);
        return map;
    }

    private static ImageUpdate nginxTo(String tag) {
        return new ImageUpdate("nginx", tag);
    }

    /**
     * Many repositories in parallel, one of them failing at the platform. The other repositories
     * must complete fully and the failure must be reported, not propagated.
     */
    @Test
    public void oneRepositoryFailingDoesNotAffectOthers() {
        InMemoryCodeHost host = new InMemoryCodeHost();
        List<ImageReference> references = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            String repo = "acme/service-" + i;
            host.addRepo(repo, files("Dockerfile", "FROM nginx:1.25\n"));
            references.add(new ImageReference(repo, "Dockerfile"));
        }
        host.failOn(Write.CREATE_BRANCH, "acme/service-3", 100, "simulated platform outage");

        List<ReconcileResult> results = new ImageUpdateReconciler(host, 4).reconcileAll(nginxTo("1.27"), references);

        assertEquals(results.size(), 8);
        int failures = 0;
        for (ReconcileResult result : results) {
            if (result.getRepository().equals("acme/service-3")) {
                assertEquals(result.getAction(), ReconcileAction.FAILED);
                assertTrue(result.getNote().contains("simulated platform outage"), result.getNote());
                failures++;
            } else {
                assertEquals(result.getAction(), ReconcileAction.CREATED, result.toString());
                assertEquals(host.pullRequestCount(result.getRepository()), 1);
                assertEquals(host.fileOn(result.getRepository(), result.getBranchName(), "Dockerfile"),
                        "FROM nginx:1.27\n");
            }
        }
        assertEquals(failures, 1);
        assertEquals(host.pullRequestCount("acme/service-3"), 0);
    }

    /**
     * A rerun after a partial failure must skip the repositories that already succeeded (no writes)
     * and finish the failed one.
     */
    @Test
    public void rerunSkipsSuccessfulRepositoriesAndFinishesFailedOnes() {
        InMemoryCodeHost host = new InMemoryCodeHost();
        List<ImageReference> references = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            String repo = "acme/service-" + i;
            host.addRepo(repo, files("Dockerfile", "FROM nginx:1.25\n"));
            references.add(new ImageReference(repo, "Dockerfile"));
        }
        host.failOn(Write.CREATE_PULL_REQUEST, "acme/service-2", 1, "transient failure");
        ImageUpdateReconciler reconciler = new ImageUpdateReconciler(host, 4);

        reconciler.reconcileAll(nginxTo("1.27"), references);

        host.resetCounters();
        List<ReconcileResult> second = reconciler.reconcileAll(nginxTo("1.27"), references);

        for (ReconcileResult result : second) {
            if (result.getRepository().equals("acme/service-2")) {
                assertEquals(result.getAction(), ReconcileAction.RESUMED_INCOMPLETE_WORK);
            } else {
                assertEquals(result.getAction(), ReconcileAction.BRANCH_ALREADY_CURRENT);
            }
        }
        assertEquals(host.writeCount(Write.CREATE_BRANCH), 0, "successful repositories must not be touched again");
        assertEquals(host.writeCount(Write.CREATE_PULL_REQUEST), 1, "only the failed repository's request is created");
        for (int i = 0; i < 4; i++) {
            assertEquals(host.pullRequestCount("acme/service-" + i), 1, "repo " + i);
            assertEquals(host.branchCount("acme/service-" + i), 1, "repo " + i);
        }
    }

    /**
     * Same branch created concurrently by two executors. The fake host's fence makes both threads
     * pass the "does the branch exist?" check before either creates it, so one of them genuinely
     * receives the already-exists conflict. The tool must treat that as "someone else created it"
     * and reuse the branch rather than failing.
     */
    @Test
    public void concurrentCreationOfTheSameBranchIsAdoptedNotFailed() throws Exception {
        final InMemoryCodeHost host = new InMemoryCodeHost()
                .addRepo("acme/service", files("Dockerfile", "FROM nginx:1.25\n"));
        host.armCreateBranchFence(2);

        final ImageUpdate update = nginxTo("1.27");
        final List<ImageReference> references = new ArrayList<>();
        references.add(new ImageReference("acme/service", "Dockerfile"));

        final ConcurrentLinkedQueue<ReconcileResult> results = new ConcurrentLinkedQueue<>();
        Thread first = worker(host, update, references, results);
        Thread second = worker(host, update, references, results);
        first.start();
        second.start();
        first.join(30000);
        second.join(30000);
        host.disarmCreateBranchFence();

        assertEquals(results.size(), 2);
        boolean sawCreatedOrResumed = false;
        for (ReconcileResult result : results) {
            assertFalse(result.getAction() == ReconcileAction.FAILED,
                    "race must not fail the repository: " + result.getNote());
            assertTrue(result.getAction() == ReconcileAction.CREATED
                            || result.getAction() == ReconcileAction.RESUMED_INCOMPLETE_WORK
                            || result.getAction() == ReconcileAction.UPDATED
                            || result.getAction() == ReconcileAction.BRANCH_ALREADY_CURRENT,
                    "unexpected action " + result.getAction());
            sawCreatedOrResumed = true;
        }
        assertTrue(sawCreatedOrResumed);
        assertEquals(host.writeCount(Write.CREATE_BRANCH), 2,
                "both threads attempted creation; one was rejected as a conflict");
        assertEquals(host.branchCount("acme/service"), 1, "exactly one branch exists");
        assertEquals(host.pullRequestCount("acme/service"), 1, "exactly one request exists");
        assertEquals(host.fileOn("acme/service", "dfiu/docker.io/library/nginx", "Dockerfile"),
                "FROM nginx:1.27\n");
    }

    private static Thread worker(final InMemoryCodeHost host, final ImageUpdate update,
                                 final List<ImageReference> references,
                                 final ConcurrentLinkedQueue<ReconcileResult> results) {
        return new Thread(() -> {
            List<ReconcileResult> outcomes = new ImageUpdateReconciler(host, 1).reconcileAll(update, references);
            results.addAll(outcomes);
        });
    }

    /** Plans that share a file in one repository are put in different rounds; others share a round. */
    @Test
    public void conflictingPlansArePartitionedIntoSeparateRounds() {
        List<UpdatePlan> plans = new ArrayList<>();
        plans.add(plan("acme/a", "nginx", "Dockerfile"));
        plans.add(plan("acme/a", "postgres", "Dockerfile"));
        plans.add(plan("acme/a", "redis", "redis.Dockerfile"));
        plans.add(plan("acme/b", "nginx", "Dockerfile"));

        List<List<UpdatePlan>> rounds = ImageUpdateReconciler.conflictFreeRounds(plans);

        assertEquals(rounds.size(), 2, "the two Dockerfile plans conflict; the others do not");
        assertEquals(rounds.get(0).size(), 3);
        assertEquals(rounds.get(1).size(), 1);
    }

    private static UpdatePlan plan(String repo, String image, String path) {
        return new UpdatePlan(repo,
                java.util.Collections.singletonList(new ImageUpdate(image, "2")),
                java.util.Collections.singletonList(path));
    }
}
