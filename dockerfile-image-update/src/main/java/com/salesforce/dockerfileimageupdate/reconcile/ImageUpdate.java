package com.salesforce.dockerfileimageupdate.reconcile;

import java.util.Objects;

/** Request to move one image to one tag across every repository that references it. */
public final class ImageUpdate {
    private final ImageRef image;
    private final String targetTag;

    public ImageUpdate(String image, String targetTag) {
        this.image = ImageRef.parse(image);
        if (targetTag == null || targetTag.trim().isEmpty()) {
            throw new IllegalArgumentException("Target tag must not be blank");
        }
        this.targetTag = targetTag.trim();
    }

    public ImageRef getImage() {
        return image;
    }

    public String getTargetTag() {
        return targetTag;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof ImageUpdate)) {
            return false;
        }
        ImageUpdate that = (ImageUpdate) o;
        return image.equals(that.image) && targetTag.equals(that.targetTag);
    }

    @Override
    public int hashCode() {
        return Objects.hash(image, targetTag);
    }

    @Override
    public String toString() {
        return image + ":" + targetTag;
    }
}
