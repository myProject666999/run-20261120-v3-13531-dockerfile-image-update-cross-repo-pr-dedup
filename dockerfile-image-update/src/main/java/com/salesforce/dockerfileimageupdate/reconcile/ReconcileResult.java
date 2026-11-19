package com.salesforce.dockerfileimageupdate.reconcile;

import java.util.Collections;
import java.util.List;

/** Outcome for one (repository, image) group. */
public final class ReconcileResult {
    private final String repository;
    private final ImageRef image;
    private final String branchName;
    private final ReconcileAction action;
    private final List<String> files;
    private final List<String> previousTags;
    private final String targetTag;
    private final String note;

    public ReconcileResult(String repository,
                           ImageRef image,
                           String branchName,
                           ReconcileAction action,
                           List<String> files,
                           List<String> previousTags,
                           String targetTag,
                           String note) {
        this.repository = repository;
        this.image = image;
        this.branchName = branchName;
        this.action = action;
        this.files = files == null ? Collections.<String>emptyList() : Collections.unmodifiableList(files);
        this.previousTags = previousTags == null ? Collections.<String>emptyList() : Collections.unmodifiableList(previousTags);
        this.targetTag = targetTag;
        this.note = note;
    }

    public String getRepository() {
        return repository;
    }

    public ImageRef getImage() {
        return image;
    }

    public String getBranchName() {
        return branchName;
    }

    public ReconcileAction getAction() {
        return action;
    }

    public List<String> getFiles() {
        return files;
    }

    public List<String> getPreviousTags() {
        return previousTags;
    }

    public String getTargetTag() {
        return targetTag;
    }

    public String getNote() {
        return note;
    }

    public boolean isFailure() {
        return action == ReconcileAction.FAILED;
    }

    @Override
    public String toString() {
        return repository + " " + image + " -> " + targetTag + ": " + action
                + (note == null ? "" : " (" + note + ")");
    }
}
