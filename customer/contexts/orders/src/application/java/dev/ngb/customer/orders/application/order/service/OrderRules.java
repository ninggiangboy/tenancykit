package dev.ngb.customer.orders.application.order.service;

import dev.ngb.core.idempotency.Fingerprint;
import dev.ngb.customer.orders.application.order.command.RecordOrderUseCase;
import dev.ngb.customer.orders.application.order.exception.InvalidOrderException;
import dev.ngb.customer.orders.application.order.exception.LineLimitExceededException;
import dev.ngb.customer.orders.domain.order.OrderLine;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * What an order must be before it is recorded: the contract's rules in one place, so the single call and the batch
 * item cannot disagree about what an acceptable order is.
 *
 * <p>{@link #plan} turns a request into the values that are stored and the fingerprint that tells a replay from a
 * changed body. The fingerprint covers what the request states and nothing that only decides how it is applied —
 * {@code createCustomer} is not part of it, because it does not change the order that is stored.
 *
 * <p>Amounts are minor units and never negative; a quantity is positive with at most three fraction digits, because a
 * scale or a weight sells a fraction of an item. A line's number is its position, starting at 1, so a caller never
 * sends one.
 */
public final class OrderRules {

    /** The longest reference the Tenant may choose for an order. */
    public static final int MAX_ORDER_REF = 200;

    /** The longest reference the Tenant may choose for a customer. */
    public static final int MAX_EXTERNAL_REF = 128;

    public static final int MAX_SKU = 100;

    public static final int MAX_NAME = 500;

    public static final int MAX_CATEGORY = 100;

    /** The longest store code and channel name, matching the event schema's own bound. */
    public static final int MAX_DESCRIPTOR = 100;

    /** The largest quantity a line may carry: {@code numeric(12, 3)} cannot hold more. */
    private static final BigDecimal MAX_QUANTITY = new BigDecimal("1000000000");

    private static final Pattern CURRENCY = Pattern.compile("^[A-Z]{3}$");

    private OrderRules() {}

    /** An order request checked against every rule, with the fingerprint of what it states. */
    public record Planned(
            String orderRef,
            String externalRef,
            String currency,
            long total,
            Instant occurredAt,
            String storeCode,
            String channel,
            List<OrderLine> lines,
            String fingerprint) {}

    /** @throws InvalidOrderException when the order is not acceptable, {@link LineLimitExceededException} when it is too large */
    public static Planned plan(RecordOrderUseCase.Command command, Instant now, int maxLines, Duration maxFutureSkew) {
        if (command == null) {
            throw new InvalidOrderException("An order is required.");
        }
        String orderRef = reference(command.orderRef(), MAX_ORDER_REF, "order");
        String externalRef = reference(command.externalRef(), MAX_EXTERNAL_REF, "customer");
        String currency = currency(command.currency());
        long total = amount(command.total(), "total");
        Instant occurredAt = occurredAt(command.occurredAt(), now, maxFutureSkew, "An order");
        String storeCode = descriptor(command.storeCode(), "storeCode");
        String channel = descriptor(command.channel(), "channel");
        List<OrderLine> lines = lines(command.lines(), maxLines);
        return new Planned(orderRef, externalRef, currency, total, occurredAt, storeCode, channel, lines,
                fingerprint(externalRef, currency, total, occurredAt, storeCode, channel, lines));
    }

    /** The Tenant's reference, trimmed: it is the natural key and the path of every call about the resource. */
    public static String reference(String raw, int max, String what) {
        if (raw == null) {
            throw new InvalidOrderException("A " + what + " reference is required.");
        }
        String ref = raw.strip();
        if (ref.isEmpty() || ref.length() > max) {
            throw new InvalidOrderException("A " + what + " reference is 1 to " + max + " characters.");
        }
        return ref;
    }

    public static String currency(String raw) {
        String code = raw == null ? null : raw.strip();
        if (code == null || !CURRENCY.matcher(code).matches()) {
            throw new InvalidOrderException("A currency is a three-letter upper-case ISO 4217 code.");
        }
        return code;
    }

    /** A money amount in minor units: present and never negative. */
    public static long amount(Long raw, String what) {
        if (raw == null) {
            throw new InvalidOrderException("The " + what + " is required.");
        }
        if (raw < 0) {
            throw new InvalidOrderException("The " + what + " must not be negative.");
        }
        return raw;
    }

    /**
     * When the order was placed: at most a few minutes in the future, and otherwise unbounded, because a Tenant that
     * imports a year of history sends dates no lower bound can accept.
     */
    public static Instant occurredAt(Instant raw, Instant now, Duration maxFutureSkew, String what) {
        if (raw == null) {
            throw new InvalidOrderException(what + " needs an occurredAt.");
        }
        if (raw.isAfter(now.plus(maxFutureSkew))) {
            throw new InvalidOrderException(what + " may be at most " + maxFutureSkew.toMinutes()
                    + " minutes in the future.");
        }
        return raw;
    }

    /** A store code or a channel: required, bounded, and stored exactly as the Tenant sent it. */
    public static String descriptor(String raw, String what) {
        if (raw == null) {
            throw new InvalidOrderException("A " + what + " is required.");
        }
        if (raw.length() > MAX_DESCRIPTOR) {
            throw new InvalidOrderException("A " + what + " is at most " + MAX_DESCRIPTOR + " characters.");
        }
        return raw;
    }

    private static List<OrderLine> lines(List<RecordOrderUseCase.Line> sent, int maxLines) {
        if (sent == null || sent.isEmpty()) {
            throw new InvalidOrderException("An order carries at least one line.");
        }
        if (sent.size() > maxLines) {
            throw new LineLimitExceededException(
                    "An order carries at most " + maxLines + " lines; send the rest as another order or as a batch.");
        }
        List<OrderLine> lines = new ArrayList<>(sent.size());
        for (int index = 0; index < sent.size(); index++) {
            RecordOrderUseCase.Line line = sent.get(index);
            lines.add(sold(index + 1, line));
        }
        return List.copyOf(lines);
    }

    private static OrderLine sold(int lineNo, RecordOrderUseCase.Line line) {
        if (line == null) {
            throw new InvalidOrderException("Line " + lineNo + " is empty.");
        }
        String sku = text(line.sku(), MAX_SKU, "line " + lineNo + " has no sku");
        String category = text(line.category(), MAX_CATEGORY, "line " + lineNo + " has no category");
        String name = line.name() == null ? null : text(line.name(), MAX_NAME, "line " + lineNo + " has no name");
        BigDecimal quantity = quantity(line.quantity(), lineNo);
        long amount = amount(line.amount(), "amount of line " + lineNo);
        boolean earnEligible = line.earnEligible() == null || line.earnEligible();
        return OrderLine.sold(lineNo, sku, name, category, quantity, amount, earnEligible);
    }

    private static String text(String raw, int max, String missing) {
        if (raw == null) {
            throw new InvalidOrderException(missing + ".");
        }
        String value = raw.strip();
        if (value.isEmpty() || value.length() > max) {
            throw new InvalidOrderException(missing + " or it is longer than " + max + " characters.");
        }
        return value;
    }

    /** A quantity is positive, carries at most three fraction digits and fits the column it is stored in. */
    public static BigDecimal quantity(BigDecimal raw, int lineNo) {
        if (raw == null) {
            throw new InvalidOrderException("Line " + lineNo + " has no quantity.");
        }
        if (raw.signum() <= 0) {
            throw new InvalidOrderException("The quantity of line " + lineNo + " must be positive.");
        }
        if (raw.stripTrailingZeros().scale() > 3) {
            throw new InvalidOrderException("The quantity of line " + lineNo + " carries at most three fraction digits.");
        }
        if (raw.compareTo(MAX_QUANTITY) >= 0) {
            throw new InvalidOrderException("The quantity of line " + lineNo + " is out of range.");
        }
        return raw;
    }

    /**
     * The digest of what the request states, so the same reference with the same body replays the stored order and the
     * same reference with another body is a conflict. A quantity is canonicalised, so {@code 2} and {@code 2.00} are
     * the same order.
     */
    private static String fingerprint(
            String externalRef,
            String currency,
            long total,
            Instant occurredAt,
            String storeCode,
            String channel,
            List<OrderLine> lines) {
        List<Object> parts = new ArrayList<>();
        parts.add(externalRef);
        parts.add(currency);
        parts.add(total);
        parts.add(occurredAt.toString());
        parts.add(storeCode);
        parts.add(channel);
        for (OrderLine line : lines) {
            parts.add(line.lineNo());
            parts.add(line.sku());
            parts.add(line.name() == null ? "" : line.name());
            parts.add(line.category());
            parts.add(canonical(line.quantity()));
            parts.add(line.amount());
            parts.add(line.earnEligible());
        }
        return Fingerprint.of(parts.toArray());
    }

    /** The canonical text of a quantity, so {@code 2} and {@code 2.00} are the same value in a fingerprint. */
    public static String canonical(BigDecimal quantity) {
        return quantity.stripTrailingZeros().toPlainString();
    }
}
