package dev.ngb.crm.orders.application.returns.service;

import dev.ngb.core.idempotency.Fingerprint;
import dev.ngb.crm.orders.application.order.service.OrderRules;
import dev.ngb.crm.orders.application.returns.command.RecordOrderReturnUseCase;
import dev.ngb.crm.orders.application.returns.exception.InvalidReturnException;
import dev.ngb.crm.orders.application.returns.exception.ReturnExceedsSaleException;
import dev.ngb.crm.orders.domain.order.Order;
import dev.ngb.crm.orders.domain.order.OrderLine;
import dev.ngb.crm.orders.domain.returns.ReturnLine;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * What a return must be before it is recorded, in one place.
 *
 * <p>A return names lines of one order by number and says how much of each came back. Every line it names must be a
 * line of that order, and the cumulative returned quantity and amount of a line may never exceed what was sold. The
 * check runs against the order as it is under its row lock, so two returns racing for the last unit cannot both pass
 * it: the second one sees the first one's figures.
 */
public final class ReturnRules {

    /** The same cap as an order: a return that names more lines than an order can have is refused. */
    public static final int MAX_LINES = 500;

    private ReturnRules() {}

    /** A return request checked for shape, before it is compared with the order it names. */
    public record Planned(String returnRef, Instant occurredAt, List<ReturnLine> lines, String fingerprint) {}

    /**
     * @throws InvalidReturnException when the body is not a return
     */
    public static Planned plan(
            String orderRef, RecordOrderReturnUseCase.Command command, Instant now, Duration maxFutureSkew) {
        if (command == null) {
            throw new InvalidReturnException("A return is required.");
        }
        String returnRef = OrderRules.reference(command.returnRef(), OrderRules.MAX_ORDER_REF, "return");
        Instant occurredAt = OrderRules.occurredAt(command.occurredAt(), now, maxFutureSkew, "A return");
        List<ReturnLine> lines = lines(command.lines());
        return new Planned(returnRef, occurredAt, lines, fingerprint(orderRef, occurredAt, lines));
    }

    /**
     * Confirms that the return takes back no more than the order sold.
     *
     * @throws ReturnExceedsSaleException when a cumulative figure would exceed the line
     * @throws InvalidReturnException when a line number is not in the order
     */
    public static void againstSale(Order order, List<ReturnLine> lines) {
        for (ReturnLine line : lines) {
            OrderLine sold = order.line(line.lineNo())
                    .orElseThrow(() -> new InvalidReturnException(
                            "The order has no line " + line.lineNo() + "."));
            if (sold.returnedQuantity().add(line.quantity()).compareTo(sold.quantity()) > 0) {
                throw new ReturnExceedsSaleException(
                        "Line " + line.lineNo() + " cannot return more than was sold.");
            }
            if (sold.returnedAmount() + line.amount() > sold.amount()) {
                throw new ReturnExceedsSaleException(
                        "Line " + line.lineNo() + " cannot return more money than was sold.");
            }
        }
    }

    private static List<ReturnLine> lines(List<RecordOrderReturnUseCase.Line> sent) {
        if (sent == null || sent.isEmpty()) {
            throw new InvalidReturnException("A return carries at least one line.");
        }
        if (sent.size() > MAX_LINES) {
            throw new InvalidReturnException("A return carries at most " + MAX_LINES + " lines.");
        }
        List<ReturnLine> lines = new ArrayList<>(sent.size());
        Set<Integer> seen = new HashSet<>();
        for (RecordOrderReturnUseCase.Line line : sent) {
            if (line == null) {
                throw new InvalidReturnException("A return line is empty.");
            }
            if (!seen.add(line.lineNo())) {
                throw new InvalidReturnException("A return names line " + line.lineNo() + " more than once.");
            }
            lines.add(new ReturnLine(
                    line.lineNo(), OrderRules.quantity(line.quantity(), line.lineNo()),
                    OrderRules.amount(line.amount(), "amount of line " + line.lineNo())));
        }
        return List.copyOf(lines);
    }

    /** The digest of what the return states, so a replay is told from a changed body; the order is part of it. */
    private static String fingerprint(String orderRef, Instant occurredAt, List<ReturnLine> lines) {
        List<Object> parts = new ArrayList<>();
        parts.add(orderRef);
        parts.add(occurredAt.toString());
        for (ReturnLine line : lines) {
            parts.add(line.lineNo());
            parts.add(OrderRules.canonical(line.quantity()));
            parts.add(line.amount());
        }
        return Fingerprint.of(parts.toArray());
    }
}
