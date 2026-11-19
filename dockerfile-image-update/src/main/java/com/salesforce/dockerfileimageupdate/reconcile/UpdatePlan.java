package com.salesforce.dockerfileimageupdate.reconcile;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * One unit of work: a repository, the images to move, their target tags, and the candidate files.
 *
 * <p>An update plan normally holds one image. Two images are placed in the same plan only when
 * their files intersect inside one repository, so that the combined change is one branch and one
 * request and can never conflict with itself.</p>
 */
public final class UpdatePlan {
    static final String MARKER_PREFIX = "<!-- dfiu-plan: ";

    private final String repository;
    private final List<ImageUpdate> updates;
    private final List<String> paths;

    public UpdatePlan(String repository, List<ImageUpdate> updates, List<String> paths) {
        if (repository == null || repository.trim().isEmpty()) {
            throw new IllegalArgumentException("Repository must not be blank");
        }
        if (updates == null || updates.isEmpty()) {
            throw new IllegalArgumentException("At least one image update is required");
        }
        this.repository = repository.trim();
        this.updates = Collections.unmodifiableList(new ArrayList<>(updates));
        this.paths = Collections.unmodifiableList(new ArrayList<>(new LinkedHashSet<>(paths)));
    }

    public String getRepository() {
        return repository;
    }

    public List<ImageUpdate> getUpdates() {
        return updates;
    }

    public List<String> getPaths() {
        return paths;
    }

    /** The image whose name determines this plan's branch; the first update, taken as given. */
    public ImageRef branchImage() {
        return updates.get(0).getImage();
    }

    public String branchName() {
        return BranchNames.forImage(repository, branchImage());
    }

    /** A stable, human-readable identity of the desired end state, stored in the request body. */
    public String marker() {
        StringBuilder builder = new StringBuilder(MARKER_PREFIX);
        boolean first = true;
        for (ImageUpdate update : updates) {
            if (!first) {
                builder.append(", ");
            }
            builder.append(update.getImage().normalized()).append('=').append(update.getTargetTag());
            first = false;
        }
        return builder.append(" -->").toString();
    }

    /** Extract the plan marker from a request body, or {@code null} if the body has none. */
    public static String markerIn(String body) {
        if (body == null) {
            return null;
        }
        int start = body.indexOf(MARKER_PREFIX);
        if (start < 0) {
            return null;
        }
        int end = body.indexOf("-->", start);
        if (end < 0) {
            return null;
        }
        return body.substring(start, end + 3);
    }

    /** Human-readable title listing every image and target tag. */
    public String title() {
        if (updates.size() == 1) {
            ImageUpdate update = updates.get(0);
            return "Update " + update.getImage().normalized() + " to " + update.getTargetTag();
        }
        StringBuilder builder = new StringBuilder("Update ").append(updates.size()).append(" base images: ");
        boolean first = true;
        for (ImageUpdate update : updates) {
            if (!first) {
                builder.append(", ");
            }
            builder.append(update.getImage().normalized()).append(':').append(update.getTargetTag());
            first = false;
        }
        return builder.toString();
    }
}
