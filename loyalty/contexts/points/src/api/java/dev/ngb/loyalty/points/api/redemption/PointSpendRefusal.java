package dev.ngb.loyalty.points.api.redemption;

import java.util.Objects;

/**
 * A spend the points context refused, carrying the code the API document names so a caller can branch on it instead of
 * reading a sentence.
 *
 * <p>It never carries a member's reference or an amount: the code is what a caller acts on.
 */
public class PointSpendRefusal extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** How the refusal maps to a status. */
    public enum Kind {
        /** The request cannot be applied as sent: 422. */
        INVALID,
        /** The request clashes with the state the member is in: 409. */
        CONFLICT,
        /** The member named does not exist in this tenant: 404. */
        NOT_FOUND
    }

    private final Kind kind;
    private final String code;

    public PointSpendRefusal(Kind kind, String code, String detail) {
        super(detail);
        this.kind = Objects.requireNonNull(kind, "kind");
        this.code = Objects.requireNonNull(code, "code");
    }

    public Kind kind() {
        return kind;
    }

    public String code() {
        return code;
    }
}
