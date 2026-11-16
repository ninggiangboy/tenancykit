package dev.ngb.loyalty.rewards.application.voucher.exception;

import java.util.Objects;

/**
 * A refusal of a voucher, carrying the status family and the concrete code the API document names, such as
 * {@code VOUCHER_USED}, {@code VOUCHER_EXPIRED} or {@code VOUCHER_ALREADY_USED}.
 *
 * <p>It never carries a code, a member's reference or an amount: the code is a bearer token and does not belong in an
 * error body a log may collect, and the caller branches on the refusal's own code.
 */
public final class VoucherRefusal extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** How the refusal maps to a status. */
    public enum Kind {
        /** The request was understood and cannot be applied as sent: 422. */
        INVALID,
        /** The request clashes with the state of the voucher: 409. */
        CONFLICT,
        /** No voucher of this tenant holds that code: 404. */
        NOT_FOUND
    }

    private final Kind kind;
    private final String code;

    private VoucherRefusal(Kind kind, String code, String detail) {
        super(detail);
        this.kind = Objects.requireNonNull(kind, "kind");
        this.code = Objects.requireNonNull(code, "code");
    }

    public static VoucherRefusal invalid(String detail) {
        return new VoucherRefusal(Kind.INVALID, "VALIDATION_FAILED", detail);
    }

    public static VoucherRefusal conflict(String code, String detail) {
        return new VoucherRefusal(Kind.CONFLICT, code, detail);
    }

    public static VoucherRefusal notFound(String code, String detail) {
        return new VoucherRefusal(Kind.NOT_FOUND, code, detail);
    }

    public Kind kind() {
        return kind;
    }

    public String code() {
        return code;
    }
}
