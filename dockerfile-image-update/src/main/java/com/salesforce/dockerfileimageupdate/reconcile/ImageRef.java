package com.salesforce.dockerfileimageupdate.reconcile;

import java.util.Locale;
import java.util.Objects;

/**
 * Canonical identity of a Docker image.
 *
 * <p>The same image can be written in several ways: {@code nginx}, {@code library/nginx},
 * {@code docker.io/nginx}, {@code docker.io/library/nginx}, {@code index.docker.io/library/nginx}.
 * All of those must collapse onto one identity, otherwise the tool would consider them different
 * images and open duplicate pull requests for the same update.</p>
 *
 * <p>Normalization is shared by every place that compares two images: grouping updates,
 * deriving branch names, and matching lines inside a Dockerfile or docker-compose file.</p>
 */
public final class ImageRef {
    private static final String DEFAULT_REGISTRY = "docker.io";
    private static final String DEFAULT_NAMESPACE = "library";

    private final String normalized;

    private ImageRef(String normalized) {
        this.normalized = normalized;
    }

    /**
     * Parse and normalize an image reference. A digest suffix is dropped; tags are not part of
     * an image's identity and are never included here.
     *
     * @param raw image name as written by a user
     * @return canonical image reference
     * @throws IllegalArgumentException if the name cannot be normalized
     */
    public static ImageRef parse(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            throw new IllegalArgumentException("Image name must not be blank");
        }
        String value = raw.trim();
        int digest = value.indexOf('@');
        if (digest >= 0) {
            value = value.substring(0, digest);
        }
        // A tag is not part of image identity. Callers normally pass a name without a tag, but
        // be forgiving if they did not.
        value = stripTag(value);

        String registry;
        String repository;
        int firstSlash = value.indexOf('/');
        if (firstSlash < 0) {
            registry = DEFAULT_REGISTRY;
            repository = value;
        } else {
            String firstComponent = value.substring(0, firstSlash);
            if (isRegistryComponent(firstComponent)) {
                registry = canonicalRegistry(firstComponent);
                repository = value.substring(firstSlash + 1);
            } else {
                registry = DEFAULT_REGISTRY;
                repository = value;
            }
        }
        repository = collapseSlashes(repository.toLowerCase(Locale.ROOT));
        while (repository.startsWith("/")) {
            repository = repository.substring(1);
        }
        while (repository.endsWith("/")) {
            repository = repository.substring(0, repository.length() - 1);
        }
        if (repository.isEmpty()) {
            throw new IllegalArgumentException("Image name has no repository part: " + raw);
        }
        if (DEFAULT_REGISTRY.equals(registry) && !repository.contains("/")) {
            repository = DEFAULT_NAMESPACE + "/" + repository;
        }
        return new ImageRef(registry + "/" + repository);
    }

    private static boolean isRegistryComponent(String component) {
        return component.contains(".") || component.contains(":") || "localhost".equals(component);
    }

    private static String canonicalRegistry(String registry) {
        String lower = registry.toLowerCase(Locale.ROOT);
        if ("index.docker.io".equals(lower) || "registry-1.docker.io".equals(lower)) {
            return DEFAULT_REGISTRY;
        }
        return lower;
    }

    private static String stripTag(String value) {
        int lastColon = value.lastIndexOf(':');
        int lastSlash = value.lastIndexOf('/');
        if (lastColon > lastSlash) {
            return value.substring(0, lastColon);
        }
        return value;
    }

    private static String collapseSlashes(String value) {
        String result = value;
        while (result.contains("//")) {
            result = result.replace("//", "/");
        }
        return result;
    }

    /**
     * @return canonical {@code registry/namespace/name} form, always including {@code docker.io}
     */
    public String normalized() {
        return normalized;
    }

    /**
     * Compare an image name as written in a file against this identity.
     *
     * @param imageNameAsWritten value found in a FROM instruction or an {@code image:} entry
     * @return whether both names refer to the same image
     */
    public boolean matches(String imageNameAsWritten) {
        if (imageNameAsWritten == null || imageNameAsWritten.trim().isEmpty()) {
            return false;
        }
        try {
            return parse(imageNameAsWritten).equals(this);
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof ImageRef)) {
            return false;
        }
        ImageRef imageRef = (ImageRef) o;
        return normalized.equals(imageRef.normalized);
    }

    @Override
    public int hashCode() {
        return Objects.hash(normalized);
    }

    @Override
    public String toString() {
        return normalized;
    }
}
