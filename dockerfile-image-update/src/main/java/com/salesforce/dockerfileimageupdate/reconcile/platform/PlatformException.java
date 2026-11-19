package com.salesforce.dockerfileimageupdate.reconcile.platform;

/** Base class for code-host failures that should be reported but must not abort other repositories. */
public class PlatformException extends Exception {
    public PlatformException(String message) {
        super(message);
    }

    public PlatformException(String message, Throwable cause) {
        super(message, cause);
    }
}
