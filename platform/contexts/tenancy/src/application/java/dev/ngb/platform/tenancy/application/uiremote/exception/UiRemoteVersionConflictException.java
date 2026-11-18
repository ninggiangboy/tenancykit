package dev.ngb.platform.tenancy.application.uiremote.exception;

import dev.ngb.core.error.ConflictException;

/** The same version is already registered with different content, so it cannot be rewritten in place. */
public class UiRemoteVersionConflictException extends ConflictException {

    private static final long serialVersionUID = 1L;

    public UiRemoteVersionConflictException(String name, String version) {
        super("version " + version + " of " + name + " is already registered with other content; publish a new version");
    }
}
