package dev.ngb.loyalty.rewards.application.reward.exception;

import java.util.Objects;

/**
 * A refusal of the reward catalog, carrying the status family and the concrete code the API document names, such as
 * {@code REWARD_NOT_FOUND} or {@code REWARD_NOT_AVAILABLE}.
 *
 * <p>It never carries a name, a member's reference or an amount: the detail is a sentence a developer reads and the code
 * is what a caller branches on.
 */
public final class RewardRefusal extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** How the refusal maps to a status. */
    public enum Kind {
        /** The request was understood and cannot be applied as sent: 422. */
        INVALID,
        /** The request clashes with the state of the catalog: 409. */
        CONFLICT,
        /** The reward named does not exist in this tenant: 404. */
        NOT_FOUND,
        /** The update needs an {@code If-Match} header: 428. */
        PRECONDITION_REQUIRED
    }

    private final Kind kind;
    private final String code;

    private RewardRefusal(Kind kind, String code, String detail) {
        super(detail);
        this.kind = Objects.requireNonNull(kind, "kind");
        this.code = Objects.requireNonNull(code, "code");
    }

    /** A value out of range or malformed, under the code the operation documents. */
    public static RewardRefusal invalid(String detail) {
        return new RewardRefusal(Kind.INVALID, "VALIDATION_FAILED", detail);
    }

    public static RewardRefusal invalid(String code, String detail) {
        return new RewardRefusal(Kind.INVALID, code, detail);
    }

    public static RewardRefusal conflict(String code, String detail) {
        return new RewardRefusal(Kind.CONFLICT, code, detail);
    }

    public static RewardRefusal notFound(String code, String detail) {
        return new RewardRefusal(Kind.NOT_FOUND, code, detail);
    }

    /** The update needs an {@code If-Match} header, which the request did not carry. */
    public static RewardRefusal ifMatchRequired() {
        return new RewardRefusal(
                Kind.PRECONDITION_REQUIRED,
                "IF_MATCH_REQUIRED",
                "Send the ETag of the version you last read in If-Match.");
    }

    public Kind kind() {
        return kind;
    }

    public String code() {
        return code;
    }
}
