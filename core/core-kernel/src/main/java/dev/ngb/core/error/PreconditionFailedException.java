package dev.ngb.core.error;

/**
 * An {@code If-Match} precondition did not hold. Deliberately not a {@link ConflictException}: 412 means the caller
 * compared against an older state, 409 means the request clashes with the current one, and the two handlers map
 * them separately.
 */
public class PreconditionFailedException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final transient Long currentVersion;

    public PreconditionFailedException(Long currentVersion) {
        super("the resource changed since it was read");
        this.currentVersion = currentVersion;
    }

    /** The version the resource holds now, or null when the row does not exist yet. */
    public Long currentVersion() {
        return currentVersion;
    }
}
