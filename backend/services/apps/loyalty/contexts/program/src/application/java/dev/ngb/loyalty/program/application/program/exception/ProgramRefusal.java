package dev.ngb.loyalty.program.application.program.exception;

import java.util.Objects;

/**
 * A refusal the API answers with a status family and the concrete code its contract names, such as
 * {@code PROGRAM_NOT_CONFIGURED} or {@code TIER_LIST_INVALID}.
 *
 * <p>It carries no member, amount or reference: the detail is a sentence for a developer and the code is what a caller
 * branches on.
 */
public final class ProgramRefusal extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** How the refusal maps to a status: unprocessable, conflicting, absent or a missing precondition. */
    public enum Kind {
        INVALID,
        CONFLICT,
        NOT_FOUND,
        PRECONDITION_REQUIRED
    }

    private final Kind kind;
    private final String code;

    private ProgramRefusal(Kind kind, String code, String detail) {
        super(detail);
        this.kind = Objects.requireNonNull(kind, "kind");
        this.code = Objects.requireNonNull(code, "code");
    }

    /** A value out of range or malformed: the request was understood and cannot be applied as sent. */
    public static ProgramRefusal invalid(String detail) {
        return new ProgramRefusal(Kind.INVALID, "VALIDATION_FAILED", detail);
    }

    /** A value out of range or malformed, under the code the operation documents. */
    public static ProgramRefusal invalid(String code, String detail) {
        return new ProgramRefusal(Kind.INVALID, code, detail);
    }

    /** The request clashes with the state the tenant is in. */
    public static ProgramRefusal conflict(String code, String detail) {
        return new ProgramRefusal(Kind.CONFLICT, code, detail);
    }

    /** The thing named does not exist in this tenant. */
    public static ProgramRefusal notFound(String code, String detail) {
        return new ProgramRefusal(Kind.NOT_FOUND, code, detail);
    }

    /** The update needs an {@code If-Match} header, which the request did not carry. */
    public static ProgramRefusal ifMatchRequired() {
        return new ProgramRefusal(
                Kind.PRECONDITION_REQUIRED, "IF_MATCH_REQUIRED", "Send the ETag of the version you last read in If-Match.");
    }

    public Kind kind() {
        return kind;
    }

    public String code() {
        return code;
    }
}
