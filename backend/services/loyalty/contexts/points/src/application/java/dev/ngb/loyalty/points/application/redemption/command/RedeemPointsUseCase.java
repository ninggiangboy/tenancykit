package dev.ngb.loyalty.points.application.redemption.command;

import dev.ngb.core.runtime.UnitOfWork;
import dev.ngb.loyalty.points.api.redemption.PointSpender;
import dev.ngb.loyalty.points.application.earn.exception.PointsRefusal;
import dev.ngb.loyalty.points.application.member.service.MemberRules;
import java.time.Instant;
import java.util.UUID;

/**
 * Spends a member's points for the caller that asks over HTTP.
 *
 * <p>It owns the transaction the spend runs in and nothing else: the member lock, the refusal order, the lot order and
 * the idempotency rule all belong to {@link PointSpender}, which the reward path calls with the same guarantees. A key
 * that spent before answers the stored redemption and announces nothing, because nothing moved.
 */
public class RedeemPointsUseCase {

    /** One spend as the caller sends it: the points, the caller's reference and its key for this spend. */
    public record Command(String externalRef, String idempotencyKey, long points, String reference) {}

    /** The redemption and the balance it left; {@code replayed} decides 201 against 200. */
    public record Result(
            UUID redemptionId,
            String externalRef,
            long points,
            String reference,
            String status,
            long available,
            long debt,
            Instant createdAt,
            Instant reversedAt,
            boolean replayed) {}

    /** The longest key a caller may send, which is the header's own documented bound. */
    private static final int MAX_KEY = 200;

    private final UnitOfWork unitOfWork;
    private final PointSpender spender;

    public RedeemPointsUseCase(UnitOfWork unitOfWork, PointSpender spender) {
        this.unitOfWork = unitOfWork;
        this.spender = spender;
    }

    public Result execute(Command command) {
        String externalRef = MemberRules.externalRef(command.externalRef());
        String idempotencyKey = key(command.idempotencyKey());
        long points = points(command.points());
        String reference = reference(command.reference());
        PointSpender.Receipt receipt = unitOfWork.transactional(
                () -> spender.spend(new PointSpender.Command(externalRef, idempotencyKey, points, reference)));
        return new Result(
                receipt.redemptionId(),
                receipt.externalRef(),
                receipt.points(),
                receipt.reference(),
                receipt.status(),
                receipt.available(),
                receipt.debt(),
                receipt.createdAt(),
                receipt.reversedAt(),
                receipt.replayed());
    }

    /** A spend without a key could be sent twice and spend twice, so the key is required and never corrected. */
    private static String key(String raw) {
        if (raw == null || raw.isBlank()) {
            throw PointsRefusal.badRequest(
                    "IDEMPOTENCY_KEY_REQUIRED", "An Idempotency-Key header is required to spend points.");
        }
        String value = raw.strip();
        if (value.length() > MAX_KEY) {
            throw PointsRefusal.badRequest(
                    "IDEMPOTENCY_KEY_INVALID", "The Idempotency-Key has at most " + MAX_KEY + " characters.");
        }
        return value;
    }

    private static long points(long points) {
        if (points < 1) {
            throw PointsRefusal.invalid("points is required and is at least 1.");
        }
        return points;
    }

    private static String reference(String raw) {
        if (raw == null) {
            return null;
        }
        String value = raw.strip();
        if (value.isEmpty()) {
            return null;
        }
        if (value.length() > MemberRules.MAX_REF) {
            throw PointsRefusal.invalid("The redemption's reference has at most " + MemberRules.MAX_REF + " characters.");
        }
        return value;
    }
}
