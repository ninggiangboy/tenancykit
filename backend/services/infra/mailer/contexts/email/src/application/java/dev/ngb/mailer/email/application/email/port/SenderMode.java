package dev.ngb.mailer.email.application.email.port;

/** How a tenant's sending identity is obtained. Only {@code TENANT} needs a role to assume; only {@code SHARED} can rely on the task role. */
public enum SenderMode {
    /** The suite's own identity and the task role's ambient credentials. */
    SHARED,
    /** A role in the tenant's own AWS account, assumed with an external id. */
    TENANT
}
