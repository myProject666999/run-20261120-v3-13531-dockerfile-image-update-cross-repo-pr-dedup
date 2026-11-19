package com.salesforce.dockerfileimageupdate.reconcile;

import org.testng.annotations.Test;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNotEquals;
import static org.testng.Assert.assertTrue;

public class BranchNamesTest {

    @Test
    public void branchIsDerivedOnlyFromRepoAndImage() {
        ImageRef image = ImageRef.parse("docker.io/library/nginx");
        assertEquals(BranchNames.forImage("acme/service", image),
                BranchNames.forImage("acme/service", image));
    }

    @Test
    public void sameImageWrittenDifferentlyYieldsTheSameBranch() {
        String fromShort = BranchNames.forImage("acme/service", ImageRef.parse("nginx"));
        String fromLong = BranchNames.forImage("acme/service", ImageRef.parse("index.docker.io/library/nginx"));
        assertEquals(fromShort, fromLong);
        assertEquals(fromShort, "dfiu/docker.io/library/nginx");
    }

    @Test
    public void differentImagesYieldDifferentBranches() {
        assertNotEquals(BranchNames.forImage("acme/service", ImageRef.parse("nginx")),
                BranchNames.forImage("acme/service", ImageRef.parse("redis")));
    }

    @Test
    public void branchIsStableAcrossJvmRuns() {
        // A golden value: the same inputs must produce this exact name on every run and machine,
        // otherwise a rerun could not find the branch from the previous run.
        assertEquals(BranchNames.forImage("acme/service", ImageRef.parse("quay.io/org/app")),
                "dfiu/quay.io/org/app");
    }

    @Test
    public void longImageNamesStayWithinGitLimitsAndRemainDeterministic() {
        String longName = "registry.example.com/" + repeat("segment/", 30) + "app";
        String first = BranchNames.forImage("acme/service", ImageRef.parse(longName));
        String second = BranchNames.forImage("acme/service", ImageRef.parse(longName));
        assertEquals(first, second);
        assertTrue(first.length() <= BranchNames.MAX_LENGTH, "branch was " + first.length() + " chars");
        assertNotEquals(first, BranchNames.forImage("acme/other", ImageRef.parse(longName)),
                "hash must separate repositories with the same long image name");
    }

    @Test
    public void groupKeyNormalizesImageSoOneImageIsOneGroup() {
        assertEquals(BranchNames.groupKey("acme/service", ImageRef.parse("nginx")),
                BranchNames.groupKey("acme/service", ImageRef.parse("docker.io/library/nginx")));
        assertNotEquals(BranchNames.groupKey("acme/service", ImageRef.parse("nginx")),
                BranchNames.groupKey("acme/other", ImageRef.parse("nginx")));
    }

    private static String repeat(String value, int times) {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < times; i++) {
            builder.append(value);
        }
        return builder.toString();
    }
}
