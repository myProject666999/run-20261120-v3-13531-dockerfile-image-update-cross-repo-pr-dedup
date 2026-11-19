package com.salesforce.dockerfileimageupdate.reconcile;

import java.util.Objects;

/** A file in a repository that references an image. */
public final class ImageReference {
    private final String repository;
    private final String path;
    private final String imageName;

    public ImageReference(String repository, String path) {
        this(repository, path, null);
    }

    /**
     * @param repository full repository name
     * @param path file path inside the repository
     * @param imageName image name as written in the file, or {@code null} when the caller already
     *                  knows which image this reference belongs to
     */
    public ImageReference(String repository, String path, String imageName) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.path = Objects.requireNonNull(path, "path");
        this.imageName = imageName;
    }

    public String getRepository() {
        return repository;
    }

    public String getPath() {
        return path;
    }

    public String getImageName() {
        return imageName;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof ImageReference)) {
            return false;
        }
        ImageReference that = (ImageReference) o;
        return repository.equals(that.repository) && path.equals(that.path)
                && Objects.equals(imageName, that.imageName);
    }

    @Override
    public int hashCode() {
        return Objects.hash(repository, path, imageName);
    }

    @Override
    public String toString() {
        return repository + ":" + path;
    }
}
