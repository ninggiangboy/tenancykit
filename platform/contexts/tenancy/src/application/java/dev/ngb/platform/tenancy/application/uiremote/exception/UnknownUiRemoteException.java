package dev.ngb.platform.tenancy.application.uiremote.exception;

import dev.ngb.core.error.NotFoundException;

/** No remote has this name. */
public class UnknownUiRemoteException extends NotFoundException {

    private static final long serialVersionUID = 1L;

    public UnknownUiRemoteException(String name) {
        super("unknown UI remote: " + name);
    }
}
