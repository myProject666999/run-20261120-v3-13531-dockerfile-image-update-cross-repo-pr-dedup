package com.salesforce.dockerfileimageupdate.reconcile.platform;

/** State of a pull/merge request on the host. */
public final class PullRequestState {
    public enum Status {
        OPEN,
        MERGED,
        CLOSED
    }

    private final long number;
    private final String headBranch;
    private final Status status;
    private final String title;
    private final String body;

    public PullRequestState(long number, String headBranch, Status status, String title, String body) {
        this.number = number;
        this.headBranch = headBranch;
        this.status = status;
        this.title = title;
        this.body = body;
    }

    public long getNumber() {
        return number;
    }

    public String getHeadBranch() {
        return headBranch;
    }

    public Status getStatus() {
        return status;
    }

    public String getTitle() {
        return title;
    }

    public String getBody() {
        return body;
    }

    /** Closed without merging: the maintainers declined this change. */
    public boolean isClosedUnmerged() {
        return status == Status.CLOSED;
    }

    public boolean isMerged() {
        return status == Status.MERGED;
    }

    public boolean isOpen() {
        return status == Status.OPEN;
    }
}
