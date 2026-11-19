package com.salesforce.dockerfileimageupdate.reconcile;

import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * Deterministic branch naming.
 *
 * <p>A branch belongs to one (target repository, normalized image) pair. The name is derived only
 * from those two inputs, so two runs with the same inputs compute the same branch and can find the
 * work of the previous run. No timestamps, counters or randomness are used.</p>
 *
 * <p>Readable prefixes are used when they fit in git's limit; otherwise a stable hash preserves
 * determinism. Both forms are pure functions of the same inputs.</p>
 */
public final class BranchNames {
    /** Conservative limit, well below git's 255-byte ref component limit. */
    static final int MAX_LENGTH = 200;
    static final String PREFIX = "dfiu/";

    private BranchNames() {
    }

    /**
     * @param targetRepo full repository name, e.g. {@code acme/service}
     * @param image canonical image identity from {@link ImageRef}
     * @return deterministic branch name
     */
    public static String forImage(String targetRepo, ImageRef image) {
        if (targetRepo == null || targetRepo.trim().isEmpty()) {
            throw new IllegalArgumentException("Target repository must not be blank");
        }
        String slug = slug(image.normalized());
        String candidate = PREFIX + slug;
        if (candidate.length() <= MAX_LENGTH) {
            return candidate;
        }
        return PREFIX + truncate(slug, 120) + "-" + hash(targetRepo + "\n" + image.normalized());
    }

    /**
     * The grouping key for an image in a repository. Every Dockerfile that references the same
     * normalized image shares one key, regardless of how the image was written.
     *
     * @param targetRepo repository name
     * @param image canonical image identity
     * @return grouping key
     */
    public static String groupKey(String targetRepo, ImageRef image) {
        return targetRepo.trim() + "\u0000" + image.normalized();
    }

    static String slug(String value) {
        String lower = value.toLowerCase(Locale.ROOT);
        StringBuilder builder = new StringBuilder(lower.length());
        boolean lastWasDash = false;
        for (int i = 0; i < lower.length(); i++) {
            char c = lower.charAt(i);
            boolean allowed = (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '.' || c == '-' || c == '_' || c == '/';
            if (allowed) {
                builder.append(c);
                lastWasDash = false;
            } else if (!lastWasDash) {
                builder.append('-');
                lastWasDash = true;
            }
        }
        String result = builder.toString();
        while (result.endsWith("/") || result.endsWith("-")) {
            result = result.substring(0, result.length() - 1);
        }
        if (result.isEmpty()) {
            return "image";
        }
        return result;
    }

    private static String truncate(String value, int length) {
        String truncated = value.length() <= length ? value : value.substring(0, length);
        while (truncated.endsWith("/") || truncated.endsWith("-")) {
            truncated = truncated.substring(0, truncated.length() - 1);
        }
        return truncated;
    }

    private static String hash(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (int i = 0; i < 8; i++) {
                hex.append(String.format("%02x", bytes[i]));
            }
            return hex.toString();
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }
}
