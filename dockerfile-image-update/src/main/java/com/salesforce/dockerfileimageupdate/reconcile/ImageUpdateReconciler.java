package com.salesforce.dockerfileimageupdate.reconcile;

import com.salesforce.dockerfileimageupdate.reconcile.platform.CodeHost;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Runs a batch of (repository, file) references for one or more images across many repositories.
 *
 * <h2>Grouping</h2>
 * <p>Work is grouped twice: by repository, and within a repository by canonical image identity
 * ({@link ImageRef}). Several Dockerfiles that reference the same image in one repository become a
 * single branch and a single request. Two different images in one repository become two requests,
 * so rejecting one does not hold back the other.</p>
 *
 * <p>The cost of that split is a possible conflict when both groups touch the same file. Two ways
 * to handle it:</p>
 * <ul>
 *   <li><b>Re-apply the second change after the first request merges.</b> Maximum independence: a
 *       rejected image never blocks another. The price is that the second request conflicts the
 *       moment the first merges, so the tool must notice the merge and rewrite the branch, and until
 *       it does the repository carries a request that cannot be merged.</li>
 *   <li><b>Detect the shared file at grouping time and put both changes on one request.</b> No
 *       conflict is ever produced and the repository stays buildable. The price is that rejecting
 *       that one request now blocks both images.</li>
 * </ul>
 * <p>This implementation uses the second for overlapping files: such groups are merged into one
 * {@link UpdatePlan}, hence one branch and one request. For non-overlapping files the images stay
 * separate, which is where independence actually pays off. {@link BranchReconciler} additionally
 * rebuilds a tool-only branch on the current default-branch head when it has drifted, which is the
 * "re-apply later" half of the first option for the case where a merge invalidates a branch.</p>
 *
 * <h2>Concurrency and failure isolation</h2>
 * <p>Plans run on a thread pool. An exception in one plan (including a platform error) becomes a
 * {@link ReconcileAction#FAILED} result and never interrupts other plans. Plans that share a file
 * in the same repository are never scheduled concurrently: they are partitioned into conflict-free
 * rounds. Results are sorted by repository then image so reruns are comparable.</p>
 *
 * <p>Re-running is safe and convergent: a plan whose work is already done performs no writes, and
 * a failed plan is simply retried because nothing about a failure is remembered between runs.</p>
 */
public class ImageUpdateReconciler {
    private static final Logger log = LoggerFactory.getLogger(ImageUpdateReconciler.class);

    private final CodeHost host;
    private final int threads;

    public ImageUpdateReconciler(CodeHost host, int threads) {
        if (threads < 1) {
            throw new IllegalArgumentException("threads must be at least 1");
        }
        this.host = host;
        this.threads = threads;
    }

    /**
     * @param updates images and their target tags
     * @param references all files known to reference each image
     * @return one result per resulting plan, sorted for stable output
     */
    public List<ReconcileResult> reconcileAll(List<ImageUpdate> updates, Collection<ImageReference> references) {
        Map<ImageRef, ImageUpdate> updateByImage = new LinkedHashMap<>();
        for (ImageUpdate update : updates) {
            ImageUpdate previous = updateByImage.get(update.getImage());
            if (previous != null && !previous.getTargetTag().equals(update.getTargetTag())) {
                throw new IllegalArgumentException("Conflicting target tags for " + update.getImage()
                        + ": " + previous.getTargetTag() + " and " + update.getTargetTag());
            }
            updateByImage.put(update.getImage(), update);
        }

        List<UpdatePlan> plans = buildPlans(updateByImage, references);
        plans = mergeOverlapping(plans);
        plans.sort(Comparator.comparing(ImageUpdateReconciler::sortKey));
        return run(plans);
    }

    /** Convenience: a single update. */
    public List<ReconcileResult> reconcileAll(ImageUpdate update, Collection<ImageReference> references) {
        return reconcileAll(Collections.singletonList(update), references);
    }

    /**
     * Build one plan per (repository, image). A caller that passes references for only some images
     * gets plans only for those images.
     */
    static List<UpdatePlan> buildPlans(Map<ImageRef, ImageUpdate> updateByImage,
                                       Collection<ImageReference> references) {
        Map<String, Map<ImageRef, Set<String>>> byRepoAndImage = new LinkedHashMap<>();
        for (ImageReference reference : references) {
            ImageRef image = matchImage(reference, updateByImage.keySet());
            if (image == null) {
                continue;
            }
            byRepoAndImage
                    .computeIfAbsent(reference.getRepository(), k -> new LinkedHashMap<>())
                    .computeIfAbsent(image, k -> new LinkedHashSet<>())
                    .add(reference.getPath());
        }
        List<UpdatePlan> plans = new ArrayList<>();
        for (Map.Entry<String, Map<ImageRef, Set<String>>> repoEntry : byRepoAndImage.entrySet()) {
            for (Map.Entry<ImageRef, Set<String>> imageEntry : repoEntry.getValue().entrySet()) {
                plans.add(new UpdatePlan(repoEntry.getKey(),
                        Collections.singletonList(updateByImage.get(imageEntry.getKey())),
                        new ArrayList<>(imageEntry.getValue())));
            }
        }
        return plans;
    }

    /**
     * A reference does not carry the image itself, so resolve which update(s) it belongs to. When
     * the caller supplied a single image, every reference belongs to it; otherwise the reference's
     * {@link ImageReference#getImageName()} decides. See {@link ImageReference}.
     */
    private static ImageRef matchImage(ImageReference reference, Set<ImageRef> images) {
        if (images.size() == 1) {
            return images.iterator().next();
        }
        String written = reference.getImageName();
        if (written == null) {
            return null;
        }
        for (ImageRef image : images) {
            if (image.matches(written)) {
                return image;
            }
        }
        return null;
    }

    /**
     * Combine plans of the same repository whose file sets intersect, so that no two requests ever
     * modify the same file. The merged plan keeps the branch of the first image, which is stable
     * because plans are ordered deterministically.
     */
    static List<UpdatePlan> mergeOverlapping(List<UpdatePlan> plans) {
        List<UpdatePlan> merged = new ArrayList<>();
        for (UpdatePlan plan : plans) {
            boolean combined = false;
            for (int i = 0; i < merged.size(); i++) {
                UpdatePlan existing = merged.get(i);
                if (existing.getRepository().equals(plan.getRepository()) && intersects(existing, plan)) {
                    List<ImageUpdate> updates = new ArrayList<>(existing.getUpdates());
                    for (ImageUpdate update : plan.getUpdates()) {
                        if (!updates.contains(update)) {
                            updates.add(update);
                        }
                    }
                    Set<String> paths = new LinkedHashSet<>(existing.getPaths());
                    paths.addAll(plan.getPaths());
                    merged.set(i, new UpdatePlan(existing.getRepository(), updates, new ArrayList<>(paths)));
                    combined = true;
                    break;
                }
            }
            if (!combined) {
                merged.add(plan);
            }
        }
        return merged;
    }

    private static boolean intersects(UpdatePlan left, UpdatePlan right) {
        for (String path : left.getPaths()) {
            if (right.getPaths().contains(path)) {
                return true;
            }
        }
        return false;
    }

    private List<ReconcileResult> run(final List<UpdatePlan> plans) {
        if (plans.isEmpty()) {
            return Collections.emptyList();
        }
        List<List<UpdatePlan>> rounds = conflictFreeRounds(plans);
        ExecutorService pool = Executors.newFixedThreadPool(Math.min(threads, plans.size()));
        List<ReconcileResult> results = new ArrayList<>();
        try {
            for (List<UpdatePlan> round : rounds) {
                List<Future<ReconcileResult>> futures = new ArrayList<>();
                for (final UpdatePlan plan : round) {
                    futures.add(pool.submit(new Callable<ReconcileResult>() {
                        @Override
                        public ReconcileResult call() {
                            return new BranchReconciler(host).reconcile(plan);
                        }
                    }));
                }
                for (int i = 0; i < futures.size(); i++) {
                    results.add(await(futures.get(i), round.get(i)));
                }
            }
        } finally {
            pool.shutdownNow();
        }
        results.sort(Comparator.comparing(ImageUpdateReconciler::sortKey));
        return results;
    }

    private static ReconcileResult await(Future<ReconcileResult> future, UpdatePlan plan) {
        try {
            return future.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return failure(plan, "interrupted while waiting for this plan");
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            log.warn("Plan failed unexpectedly: {}", cause.getMessage());
            return failure(plan, cause.getMessage() == null ? cause.toString() : cause.getMessage());
        }
    }

    private static ReconcileResult failure(UpdatePlan plan, String message) {
        return new ReconcileResult(plan.getRepository(), plan.branchImage(), plan.branchName(),
                ReconcileAction.FAILED, Collections.<String>emptyList(),
                Collections.<String>emptyList(), plan.marker(), message);
    }

    private static String sortKey(UpdatePlan plan) {
        return plan.getRepository() + "\u0000" + plan.branchImage().normalized();
    }

    private static String sortKey(ReconcileResult result) {
        return result.getRepository() + "\u0000" + result.getImage().normalized();
    }

    /**
     * Partition plans into rounds such that no two plans in a round share a file in the same
     * repository. Plans in different repositories can always run together.
     */
    static List<List<UpdatePlan>> conflictFreeRounds(List<UpdatePlan> plans) {
        List<List<UpdatePlan>> rounds = new ArrayList<>();
        for (UpdatePlan plan : plans) {
            boolean placed = false;
            for (List<UpdatePlan> round : rounds) {
                boolean conflicts = false;
                for (UpdatePlan other : round) {
                    if (other.getRepository().equals(plan.getRepository()) && intersects(other, plan)) {
                        conflicts = true;
                        break;
                    }
                }
                if (!conflicts) {
                    round.add(plan);
                    placed = true;
                    break;
                }
            }
            if (!placed) {
                List<UpdatePlan> round = new ArrayList<>();
                round.add(plan);
                rounds.add(round);
            }
        }
        return rounds;
    }
}
