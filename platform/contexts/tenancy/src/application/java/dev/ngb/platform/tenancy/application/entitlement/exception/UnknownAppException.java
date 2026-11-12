package dev.ngb.platform.tenancy.application.entitlement.exception;

/** The app code is not one of the suite's apps. */
public class UnknownAppException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public UnknownAppException(String appCode) {
        super("unknown app: " + appCode);
    }
}
