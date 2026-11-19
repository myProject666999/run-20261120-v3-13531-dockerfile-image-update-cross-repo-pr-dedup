package com.salesforce.dockerfileimageupdate.reconcile.platform;

/** A write on the platform was rejected because another actor created the same ref first. */
public class BranchAlreadyExistsException extends PlatformException {
    public BranchAlreadyExistsException(String message) {
        super(message);
    }
}
