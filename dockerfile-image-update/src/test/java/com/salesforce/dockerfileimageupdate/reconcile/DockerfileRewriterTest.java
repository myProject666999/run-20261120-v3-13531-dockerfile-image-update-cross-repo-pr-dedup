package com.salesforce.dockerfileimageupdate.reconcile;

import org.testng.annotations.Test;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;

public class DockerfileRewriterTest {

    private static final String DOCKERFILE = String.join("\n",
            "# syntax=docker/dockerfile:1",
            "FROM nginx:1.25 AS base",
            "RUN echo hello",
            "FROM docker.io/library/nginx:1.24",
            "COPY --from=base /x /y",
            "");

    @Test
    public void rewritesEveryReferenceToTheImageRegardlessOfSpelling() {
        DockerfileRewriter.FileRewrite rewrite =
                new DockerfileRewriter(ImageRef.parse("docker.io/library/nginx"), "1.27")
                        .rewrite("Dockerfile", DOCKERFILE);
        assertTrue(rewrite.getContent().contains("FROM nginx:1.27 AS base"));
        assertTrue(rewrite.getContent().contains("FROM docker.io/library/nginx:1.27"));
        assertTrue(rewrite.getContent().endsWith("\n"), "trailing newline must be preserved");
        assertEquals(rewrite.getPreviousTags().size(), 2);
        assertTrue(rewrite.getPreviousTags().contains("1.25"));
        assertTrue(rewrite.getPreviousTags().contains("1.24"));
    }

    @Test
    public void leavesOtherImagesAndInstructionsAlone() {
        DockerfileRewriter.FileRewrite rewrite =
                new DockerfileRewriter(ImageRef.parse("redis"), "7.4")
                        .rewrite("Dockerfile", DOCKERFILE);
        assertFalse(rewrite.isChanged(DOCKERFILE));
        assertTrue(rewrite.getPreviousTags().isEmpty());
    }

    @Test
    public void isIdempotent() {
        DockerfileRewriter rewriter = new DockerfileRewriter(ImageRef.parse("nginx"), "1.27");
        String once = rewriter.rewrite("Dockerfile", DOCKERFILE).getContent();
        DockerfileRewriter.FileRewrite twice = rewriter.rewrite("Dockerfile", once);
        assertFalse(twice.isChanged(once));
        assertTrue(twice.getPreviousTags().isEmpty());
    }

    @Test
    public void rewritesComposeImageEntries() {
        String compose = String.join("\n",
                "services:",
                "  web:",
                "    image: nginx:1.25",
                "  db:",
                "    image: postgres:15",
                "");
        DockerfileRewriter.FileRewrite rewrite =
                new DockerfileRewriter(ImageRef.parse("docker.io/library/nginx"), "1.27")
                        .rewrite("docker-compose.yml", compose);
        assertTrue(rewrite.getContent().contains("image: nginx:1.27"));
        assertTrue(rewrite.getContent().contains("image: postgres:15"));
        assertEquals(rewrite.getPreviousTags().size(), 1);
        assertEquals(rewrite.getPreviousTags().get(0), "1.25");
    }

    @Test
    public void noTrailingNewlineInSourceProducesNoneInOutput() {
        String source = "FROM nginx:1.25";
        DockerfileRewriter.FileRewrite rewrite =
                new DockerfileRewriter(ImageRef.parse("nginx"), "1.27").rewrite("Dockerfile", source);
        assertEquals(rewrite.getContent(), "FROM nginx:1.27");
    }
}
