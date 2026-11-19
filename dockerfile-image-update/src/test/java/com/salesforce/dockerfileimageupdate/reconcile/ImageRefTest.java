package com.salesforce.dockerfileimageupdate.reconcile;

import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNotEquals;
import static org.testng.Assert.assertTrue;

public class ImageRefTest {

    @DataProvider
    public Object[][] equivalentSpellings() {
        return new Object[][]{
                {"nginx", "docker.io/library/nginx"},
                {"library/nginx", "docker.io/library/nginx"},
                {"docker.io/nginx", "docker.io/library/nginx"},
                {"docker.io/library/nginx", "docker.io/library/nginx"},
                {"index.docker.io/library/nginx", "docker.io/library/nginx"},
                {"  docker.io/library/nginx:1.27  ", "docker.io/library/nginx"},
                {"nginx@sha256:abcdef", "docker.io/library/nginx"},
                {"myorg/app", "docker.io/myorg/app"},
                {"quay.io/myorg/app", "quay.io/myorg/app"},
                {"QUAY.IO/myorg/app", "quay.io/myorg/app"},
                {"localhost:5000/app", "localhost:5000/app"},
                {"ghcr.io/org/app", "ghcr.io/org/app"},
        };
    }

    @Test(dataProvider = "equivalentSpellings")
    public void normalizesToCanonicalIdentity(String written, String expected) {
        assertEquals(ImageRef.parse(written).normalized(), expected);
    }

    @Test(dataProvider = "equivalentSpellings")
    public void writtenAndCanonicalNamesAreTheSameImage(String written, String canonical) {
        ImageRef identity = ImageRef.parse(canonical);
        assertTrue(identity.matches(written), written + " should match " + canonical);
        assertEquals(ImageRef.parse(written), identity);
    }

    @Test
    public void differentRegistriesAreDifferentImages() {
        assertNotEquals(ImageRef.parse("quay.io/myorg/app"), ImageRef.parse("myorg/app"));
    }

    @Test
    public void differentNamespacesAreDifferentImages() {
        assertNotEquals(ImageRef.parse("acme/nginx"), ImageRef.parse("nginx"));
    }

    @Test
    public void blankNamesAreRejected() {
        for (String blank : new String[]{null, "", "   ", "/", "docker.io/"}) {
            try {
                ImageRef.parse(blank);
                throw new AssertionError("expected rejection of <" + blank + ">");
            } catch (IllegalArgumentException expected) {
                // expected
            }
        }
    }

    @Test
    public void doesNotMatchUnrelatedNames() {
        ImageRef nginx = ImageRef.parse("nginx");
        assertFalse(nginx.matches("nginxx"));
        assertFalse(nginx.matches("mynginx"));
        assertFalse(nginx.matches("acme/nginx"));
        assertFalse(nginx.matches(null));
    }
}
