package com.salesforce.dockerfileimageupdate.reconcile;

import com.salesforce.dockerfileimageupdate.model.FromInstruction;
import com.salesforce.dockerfileimageupdate.model.ImageKeyValuePair;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Rewrites Dockerfile {@code FROM} lines and docker-compose {@code image:} entries so that they
 * point at {@code targetTag}.
 *
 * <p>The image is matched through {@link ImageRef}, so {@code nginx:1.25} is updated even if the
 * request was expressed as {@code docker.io/library/nginx:1.27}.</p>
 */
public final class DockerfileRewriter {

    /** Result of rewriting one file. Immutable. */
    public static final class FileRewrite {
        private final String path;
        private final String content;
        private final List<String> previousTags;

        FileRewrite(String path, String content, List<String> previousTags) {
            this.path = path;
            this.content = content;
            this.previousTags = Collections.unmodifiableList(new ArrayList<>(previousTags));
        }

        public String getPath() {
            return path;
        }

        public String getContent() {
            return content;
        }

        /** @return tags the image had before the rewrite, in file order, without duplicates */
        public List<String> getPreviousTags() {
            return previousTags;
        }

        public boolean isChanged(String original) {
            return !Objects.equals(original, content);
        }
    }

    private final ImageRef image;
    private final String targetTag;

    public DockerfileRewriter(ImageRef image, String targetTag) {
        if (targetTag == null || targetTag.trim().isEmpty()) {
            throw new IllegalArgumentException("Target tag must not be blank");
        }
        this.image = Objects.requireNonNull(image, "image");
        this.targetTag = targetTag.trim();
    }

    /**
     * Rewrite a file's full content.
     *
     * @param path file path, used only for reporting
     * @param content current content
     * @return rewritten content and the tags that were replaced
     */
    public FileRewrite rewrite(String path, String content) {
        List<String> previousTags = new ArrayList<>();
        String[] lines = content.split("\n", -1);
        StringBuilder out = new StringBuilder(content.length() + 32);
        boolean endsWithNewline = content.endsWith("\n");
        int limit = endsWithNewline ? lines.length - 1 : lines.length;
        for (int i = 0; i < limit; i++) {
            String line = lines[i];
            String rewritten = line;
            String oldTag = null;
            if (FromInstruction.isFromInstruction(line)) {
                FromInstruction from = new FromInstruction(line);
                if (image.matches(from.getBaseImageName()) && from.hasADifferentTag(targetTag)) {
                    oldTag = from.hasTag() ? from.getTag() : "<none>";
                    rewritten = from.getFromInstructionWithNewTag(targetTag).toString();
                }
            } else if (ImageKeyValuePair.isImageKeyValuePair(line)) {
                ImageKeyValuePair pair = new ImageKeyValuePair(line);
                if (image.matches(pair.getBaseImageName())
                        && pair.hasADifferentTag(targetTag)
                        && com.salesforce.dockerfileimageupdate.utils.DockerfileGitHubUtil.isValidImageTag(pair.getTag())) {
                    oldTag = pair.hasTag() ? pair.getTag() : "<none>";
                    rewritten = pair.getImageKeyValuePairWithNewTag(targetTag).toString();
                }
            }
            if (oldTag != null && !previousTags.contains(oldTag)) {
                previousTags.add(oldTag);
            }
            out.append(rewritten);
            if (i < limit - 1 || endsWithNewline) {
                out.append('\n');
            }
        }
        return new FileRewrite(path, out.toString(), previousTags);
    }
}
