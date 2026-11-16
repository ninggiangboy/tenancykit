package dev.ngb.loyalty.points.application.earn.exception;

import java.util.Objects;

/**
 * A refusal of the points context, carrying the status family and the concrete code the API document names, such as
 * {@code ORDER_UNKNOWN} or {@code RETURN_EXCEEDS_SALE}.
 *
 * <p>It never carries a member's reference or an amount: the detail is a sentence a developer reads and the code is what
 * a caller branches on.
 */
public final class PointsRefusal extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** How the refusal maps to a status. */
    public enum Kind {
        /** The request was understood and cannot be applied as sent: 422. */
        INVALID,
        /** The request clashes with the state the tenant is in: 409. */
        CONFLICT,
        /** The thing named does not exist in this tenant: 404. */
        NOT_FOUND
    }

    private final Kind kind;
    private final String code;

    private PointsRefusal(Kind kind, String code, String detail) {
        super(detail);
        this.kind = Objects.requireNonNull(kind, "kind");
        this.code = Objects.requireNonNull(code, "code");
    }

    public static PointsRefusal invalid(String detail) {
        return new PointsRefusal(Kind.INVALID, "VALIDATION_FAILED", detail);
    }

    public static PointsRefusal invalid(String code, String detail) {
        return new PointsRefusal(Kind.INVALID, code, detail);
    }

    public static PointsRefusal conflict(String code, String detail) {
        return new PointsRefusal(Kind.CONFLICT, code, detail);
    }

    public static PointsRefusal notFound(String code, String detail) {
        return new PointsRefusal(Kind.NOT_FOUND, code, detail);
    }

    public Kind kind() {
        return kind;
    }

    public String code() {
        return code;
    }
}
