package dev.ngb.loyalty.points.application.order.command;

import dev.ngb.core.id.TenantId;
import dev.ngb.core.idempotency.Fingerprint;
import dev.ngb.core.runtime.Clock;
import dev.ngb.core.runtime.IdGenerator;
import dev.ngb.core.runtime.UnitOfWork;
import dev.ngb.core.tenancy.TenantContext;
import dev.ngb.loyalty.points.application.earn.exception.PointsRefusal;
import dev.ngb.loyalty.points.application.earn.service.PointsApplier;
import dev.ngb.loyalty.points.application.earn.service.PointsEvents;
import dev.ngb.loyalty.points.application.ledger.port.LedgerRepository;
import dev.ngb.loyalty.points.application.member.port.MemberRepository;
import dev.ngb.loyalty.points.application.member.port.MemberRow;
import dev.ngb.loyalty.points.application.member.service.MemberRules;
import dev.ngb.loyalty.points.application.order.port.OrderRepository;
import dev.ngb.loyalty.points.application.order.port.ReturnLineRow;
import dev.ngb.loyalty.points.application.order.port.StoredOrder;
import dev.ngb.loyalty.points.application.order.port.StoredReturn;
import dev.ngb.loyalty.points.domain.earn.EarnCalculator;
import dev.ngb.loyalty.points.domain.earn.EarnOutcome;
import dev.ngb.loyalty.points.domain.order.OrderLine;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Records one return against an order and takes back the points the order is no longer worth.
 *
 * <p>The order's new worth is recomputed with the snapshot stored when it was earned and never with the rules of today,
 * so a promotion archived in between, a changed currency unit or a moved tier cannot change what a return claws back.
 * The clawback is the difference between that worth and what the order was already granted, which is why a return is
 * safe to replay and why two returns of the last unit cannot both succeed.
 *
 * <p>A return for an order loyalty has not seen answers {@code ORDER_UNKNOWN} and writes nothing: the caller retries
 * once the order exists. The member row is locked before the order row, and the order reference is looked up once
 * without a lock only to learn which member to lock first.
 */
public class RecordReturnUseCase {

    public record Command(String orderRef, String returnRef, Instant occurredAt, List<Line> lines) {

        public record Line(int lineNo, BigDecimal quantity, long amount) {}
    }

    private final UnitOfWork unitOfWork;
    private final OrderRepository orders;
    private final MemberRepository members;
    private final LedgerRepository ledger;
    private final PointsApplier applier;
    private final EarnCalculator calculator;
    private final PointsEvents events;
    private final TenantContext tenantContext;
    private final IdGenerator ids;
    private final Clock clock;

    public RecordReturnUseCase(
            UnitOfWork unitOfWork,
            OrderRepository orders,
            MemberRepository members,
            LedgerRepository ledger,
            PointsApplier applier,
            EarnCalculator calculator,
            PointsEvents events,
            TenantContext tenantContext,
            IdGenerator ids,
            Clock clock) {
        this.unitOfWork = unitOfWork;
        this.orders = orders;
        this.members = members;
        this.ledger = ledger;
        this.applier = applier;
        this.calculator = calculator;
        this.events = events;
        this.tenantContext = tenantContext;
        this.ids = ids;
        this.clock = clock;
    }

    public RecordOrderUseCase.Result execute(Command command) {
        String orderRef = MemberRules.reference(command.orderRef(), "orderRef");
        String returnRef = MemberRules.reference(command.returnRef(), "returnRef");
        List<Command.Line> lines = returned(command.lines());
        if (command.occurredAt() == null) {
            throw PointsRefusal.invalid("occurredAt is required and is when the return happened.");
        }
        return unitOfWork.transactional(() -> record(orderRef, returnRef, command.occurredAt(), lines));
    }

    private RecordOrderUseCase.Result record(
            String orderRef, String returnRef, Instant occurredAt, List<Command.Line> lines) {
        TenantId tenantId = tenantContext.requireTenant();
        Instant now = clock.now();
        UUID owner = orders.findByRef(orderRef)
                .map(StoredOrder::memberId)
                .orElseThrow(RecordReturnUseCase::orderUnknown);
        MemberRow member = members.lockById(owner).orElseThrow(RecordReturnUseCase::orderUnknown);
        StoredOrder order = orders.lockByRef(orderRef).orElseThrow(RecordReturnUseCase::orderUnknown);
        String fingerprint = Fingerprint.of(returnRef, occurredAt, canonical(lines));

        Optional<StoredReturn> stored = orders.findReturn(returnRef);
        if (stored.isPresent()) {
            StoredReturn recorded = stored.get();
            if (!recorded.orderId().equals(order.id()) || !recorded.fingerprint().equals(fingerprint)) {
                throw PointsRefusal.conflict(
                        "IDEMPOTENCY_CONFLICT", "The same returnRef was sent before with a different body.");
            }
            return result(order, member, now, true);
        }

        List<OrderLine> linesAfter = returnedTo(orders.lines(order.id()), lines);
        EarnOutcome worth = calculator.recompute(order.snapshot(), linesAfter);
        long delta = worth.points() - order.grantedPoints();
        UUID returnId = ids.newId();
        PointsApplier.Applied applied =
                delta < 0 ? applier.takeBack(member, -delta, order.id(), returnId, now) : applier.quiet(member, now);
        StoredReturn recorded = new StoredReturn(returnId, order.id(), returnRef, occurredAt, fingerprint,
                delta < 0 ? -delta : 0L, applied.debtAdded(), now);
        orders.updateReturned(order.id(), linesAfter);
        orders.updateWorth(order.id(), worth.points(), worth.spendAmount());
        orders.addReturn(order.id(), recorded, rows(lines));
        if (applied.debtAfter() != member.debt()) {
            member = member.with(member.tierCode(), applied.debtAfter());
            members.update(member);
        }
        if (delta < 0) {
            events.pointsClawedBack(tenantId, now, member.id(), member.externalRef(), orderRef, returnRef, -delta,
                    applied.debtAdded(), applied.available(), applied.debtAfter());
        }
        return result(order.worth(worth.points(), worth.spendAmount()), member, now, false);
    }

    private RecordOrderUseCase.Result result(StoredOrder order, MemberRow member, Instant now, boolean replayed) {
        List<RecordOrderUseCase.Result.LineResult> lines = orders.lines(order.id()).stream()
                .map(line -> new RecordOrderUseCase.Result.LineResult(line.lineNo(), line.sku(), line.category(),
                        line.quantity(), line.amount(), line.earnEligible(), line.returnedQuantity(),
                        line.returnedAmount()))
                .toList();
        List<RecordOrderUseCase.Result.ReturnResult> returns = orders.returns(order.id()).stream()
                .map(taken -> new RecordOrderUseCase.Result.ReturnResult(
                        taken.returnRef(), taken.occurredAt(), taken.pointsClawedBack(), taken.debtAdded()))
                .toList();
        long available = ledger.liveBalance(member.id(), now) - member.debt();
        return new RecordOrderUseCase.Result(order.orderRef(), member.externalRef(), order.currency(),
                order.occurredAt(), order.storeCode(), order.channel(), order.grantedPoints(), order.spendAmount(),
                available, member.debt(), lines, returns, replayed);
    }

    /**
     * Applies the return to the order's lines, refusing a line the order does not have and a cumulative returned
     * quantity or amount above what was sold.
     */
    private static List<OrderLine> returnedTo(List<OrderLine> sold, List<Command.Line> lines) {
        Map<Integer, OrderLine> after = new LinkedHashMap<>();
        for (OrderLine line : sold) {
            after.put(line.lineNo(), line);
        }
        for (Command.Line returning : lines) {
            OrderLine line = after.get(returning.lineNo());
            if (line == null) {
                throw PointsRefusal.invalid("No line " + returning.lineNo() + " belongs to this order.");
            }
            if (line.returnedQuantity().add(returning.quantity()).compareTo(line.quantity()) > 0
                    || line.returnedAmount() + returning.amount() > line.amount()) {
                throw PointsRefusal.invalid(
                        "RETURN_EXCEEDS_SALE",
                        "The cumulative returned quantity or amount of line " + returning.lineNo()
                                + " exceeds what was sold.");
            }
            after.put(line.lineNo(), line.returned(returning.quantity(), returning.amount()));
        }
        return List.copyOf(after.values());
    }

    private static List<Command.Line> returned(List<Command.Line> lines) {
        if (lines == null || lines.isEmpty()) {
            throw PointsRefusal.invalid("A return has at least one line.");
        }
        Set<Integer> seen = new HashSet<>();
        List<Command.Line> returned = new ArrayList<>(lines.size());
        for (Command.Line line : lines) {
            if (line == null || !seen.add(line.lineNo())) {
                throw PointsRefusal.invalid("Every return line carries its own lineNo.");
            }
            if (line.quantity() == null || line.quantity().signum() <= 0
                    || line.quantity().stripTrailingZeros().scale() > 3) {
                throw PointsRefusal.invalid("A returned quantity is positive with at most three fraction digits.");
            }
            if (line.amount() < 0) {
                throw PointsRefusal.invalid("A returned amount must not be negative.");
            }
            returned.add(line);
        }
        return List.copyOf(returned);
    }

    private static List<ReturnLineRow> rows(List<Command.Line> lines) {
        return lines.stream().map(line -> new ReturnLineRow(line.lineNo(), line.quantity(), line.amount())).toList();
    }

    private static List<Object> canonical(List<Command.Line> lines) {
        List<Object> parts = new ArrayList<>(lines.size());
        for (Command.Line line : lines) {
            parts.add(List.of(line.lineNo(), line.quantity().toPlainString(), line.amount()));
        }
        return parts;
    }

    private static PointsRefusal orderUnknown() {
        return PointsRefusal.invalid(
                "ORDER_UNKNOWN", "loyalty has not seen this order yet; retry once it has been recorded.");
    }
}
