package dev.ngb.loyalty.points.application.order.command;

import dev.ngb.core.idempotency.Fingerprint;
import dev.ngb.core.id.TenantId;
import dev.ngb.core.runtime.Clock;
import dev.ngb.core.runtime.IdGenerator;
import dev.ngb.core.runtime.UnitOfWork;
import dev.ngb.core.tenancy.TenantContext;
import dev.ngb.loyalty.points.application.earn.exception.PointsRefusal;
import dev.ngb.loyalty.points.application.earn.service.PointsApplier;
import dev.ngb.loyalty.points.application.earn.service.PointsEvents;
import dev.ngb.loyalty.points.application.earn.service.TierSelection;
import dev.ngb.loyalty.points.application.earn.port.TierEvaluator;
import dev.ngb.loyalty.points.application.ledger.port.LedgerRepository;
import dev.ngb.loyalty.points.application.member.port.MemberRepository;
import dev.ngb.loyalty.points.application.member.port.MemberRow;
import dev.ngb.loyalty.points.application.member.service.MemberRules;
import dev.ngb.loyalty.points.application.order.port.OrderRepository;
import dev.ngb.loyalty.points.application.order.port.StoredOrder;
import dev.ngb.loyalty.points.application.order.port.StoredReturn;
import dev.ngb.loyalty.points.domain.earn.EarnCalculator;
import dev.ngb.loyalty.points.domain.earn.EarnContext;
import dev.ngb.loyalty.points.domain.earn.EarnOutcome;
import dev.ngb.loyalty.points.domain.order.OrderLine;
import dev.ngb.loyalty.program.api.program.ProgramReader;
import dev.ngb.loyalty.program.api.program.ProgramView;
import dev.ngb.loyalty.program.api.tier.TierView;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Records one order once and grants the points it earns.
 *
 * <p>The order is immutable once recorded and the call is idempotent by its reference: the same reference with the same
 * body replays the stored answer, and the same reference with a different body is a conflict rather than a silent
 * overwrite. The first order for an unknown member reference enrols that member in the same transaction.
 *
 * <p>The order of the steps is the order of the locks and the checks the plan fixes: the member row is locked first, so
 * two calls for one member run one after the other; the program must exist and match the order's currency before
 * anything is written; and the order reference is checked only after the member lock, so a replay cannot interleave with
 * a first recording.
 */
public class RecordOrderUseCase {

    /** At most this many lines in one order. */
    public static final int LINE_LIMIT = 500;

    /** How far ahead of now an order may claim to have happened. */
    public static final Duration FUTURE_TOLERANCE = Duration.ofMinutes(5);

    private static final Pattern CURRENCY = Pattern.compile("^[A-Z]{3}$");

    public record Command(
            String orderRef,
            String externalRef,
            String currency,
            Instant occurredAt,
            String storeCode,
            String channel,
            List<Line> lines) {

        public record Line(
                int lineNo, String sku, String category, BigDecimal quantity, long amount, Boolean earnEligible) {}
    }

    /** What the order is worth after this call, and what it earned, shared with the return flow's answer. */
    public record Result(
            String orderRef,
            String memberExternalRef,
            String currency,
            Instant occurredAt,
            String storeCode,
            String channel,
            long pointsGranted,
            long spendAmount,
            long available,
            long debt,
            List<LineResult> lines,
            List<ReturnResult> returns,
            boolean replayed) {

        public record LineResult(
                int lineNo,
                String sku,
                String category,
                BigDecimal quantity,
                long amount,
                boolean earnEligible,
                BigDecimal returnedQuantity,
                long returnedAmount) {}

        public record ReturnResult(
                String returnRef, Instant occurredAt, long pointsClawedBack, long debtAdded) {}
    }

    private final UnitOfWork unitOfWork;
    private final OrderRepository orders;
    private final MemberRepository members;
    private final LedgerRepository ledger;
    private final PointsApplier applier;
    private final EarnCalculator calculator;
    private final PointsEvents events;
    private final TierEvaluator tierEvaluator;
    private final ProgramReader programReader;
    private final TenantContext tenantContext;
    private final IdGenerator ids;
    private final Clock clock;

    public RecordOrderUseCase(
            UnitOfWork unitOfWork,
            OrderRepository orders,
            MemberRepository members,
            LedgerRepository ledger,
            PointsApplier applier,
            EarnCalculator calculator,
            PointsEvents events,
            TierEvaluator tierEvaluator,
            ProgramReader programReader,
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
        this.tierEvaluator = tierEvaluator;
        this.programReader = programReader;
        this.tenantContext = tenantContext;
        this.ids = ids;
        this.clock = clock;
    }

    public Result execute(Command command) {
        String orderRef = MemberRules.reference(command.orderRef(), "orderRef");
        String externalRef = MemberRules.externalRef(command.externalRef());
        List<OrderLine> lines = sold(command.lines());
        if (command.occurredAt() == null) {
            throw PointsRefusal.invalid("occurredAt is required and is when the order happened.");
        }
        return unitOfWork.transactional(() -> record(orderRef, externalRef, command, lines));
    }

    private Result record(String orderRef, String externalRef, Command command, List<OrderLine> lines) {
        TenantId tenantId = tenantContext.requireTenant();
        Instant now = clock.now();
        requireNotInTheFuture(command.occurredAt(), now);
        ProgramView program = programReader
                .program()
                .orElseThrow(() -> PointsRefusal.conflict(
                        "PROGRAM_NOT_CONFIGURED", "No points program is configured yet, so nothing can earn."));
        String currency = currency(command.currency(), program);
        String storeCode = text(command.storeCode(), 100);
        String channel = text(command.channel(), 50);
        String fingerprint = Fingerprint.of(
                externalRef, currency, command.occurredAt(), storeCode, channel, canonical(lines));

        MemberRow member = lockMember(tenantId, externalRef, now);
        Optional<StoredOrder> stored = orders.findByRef(orderRef);
        if (stored.isPresent()) {
            return replay(stored.get(), member, fingerprint, now);
        }

        Optional<TierView> tier = TierSelection.current(member.tierCode(), programReader.tiers());
        EarnContext context = new EarnContext(
                program.unitAmount(),
                currency,
                tier.map(TierView::code).orElse(null),
                tier.map(TierView::earnMultiplier).orElse(BigDecimal.ONE),
                command.occurredAt(),
                storeCode,
                channel,
                lines,
                programReader.activePromotionsAt(command.occurredAt()));
        EarnOutcome earned = calculator.evaluate(context);

        UUID orderId = ids.newId();
        StoredOrder order = new StoredOrder(orderId, orderRef, member.id(), currency, command.occurredAt(), storeCode,
                channel, fingerprint, now, 0L, 0L, earned.snapshot());
        orders.add(order, lines);
        PointsApplier.Applied applied = earned.points() > 0
                ? applier.grant(member, earned.points(), orderId, now, program.expiryMonths())
                : applier.quiet(member, now);
        MemberRow after = remember(member, tier.map(TierView::code).orElse(null), applied);
        orders.updateWorth(orderId, earned.points(), earned.spendAmount());
        if (earned.points() > 0) {
            events.pointsEarned(tenantId, now, member.id(), externalRef, orderRef, earned.points(),
                    applied.debtRepaid(), applied.available(), applied.debtAfter());
        }
        tierEvaluator.afterEarn(member.id());
        return result(order.worth(earned.points(), earned.spendAmount()), after, now, false);
    }

    /**
     * Locks the member the reference names, enrolling it first when the reference is new. The member row is the first lock
     * of the flow, so a replay, a return and a second order for one member cannot each read the same debt.
     */
    private MemberRow lockMember(TenantId tenantId, String externalRef, Instant now) {
        Optional<MemberRow> existing = members.lockByRef(externalRef);
        if (existing.isPresent()) {
            return existing.get();
        }
        MemberRow created = MemberRow.enrolled(ids.newId(), externalRef, null, now);
        if (!members.addIfAbsent(created)) {
            return members.lockByRef(externalRef)
                    .orElseThrow(() -> new IllegalStateException("the member row was inserted and then not found"));
        }
        events.memberEnrolled(tenantId, created.id(), externalRef, now, true);
        return created;
    }

    /** Keeps the tier the earn used and the debt it left, in one write, and only when either changed. */
    private MemberRow remember(MemberRow member, String tierCode, PointsApplier.Applied applied) {
        String tier = member.tierCode() == null ? tierCode : member.tierCode();
        if (applied.debtAfter() == member.debt() && Objects.equals(tier, member.tierCode())) {
            return member;
        }
        MemberRow after = member.with(tier, applied.debtAfter());
        members.update(after);
        return after;
    }

    private Result replay(StoredOrder order, MemberRow member, String fingerprint, Instant now) {
        if (!order.fingerprint().equals(fingerprint)) {
            throw PointsRefusal.conflict(
                    "IDEMPOTENCY_CONFLICT", "The same orderRef was sent before with a different body.");
        }
        MemberRow owner = order.memberId().equals(member.id())
                ? member
                : members.lockById(order.memberId())
                        .orElseThrow(() -> PointsRefusal.notFound(
                                "MEMBER_NOT_FOUND", "The member this order belongs to does not exist."));
        return result(order, owner, now, true);
    }

    private Result result(StoredOrder order, MemberRow member, Instant now, boolean replayed) {
        List<Result.LineResult> lines = orders.lines(order.id()).stream()
                .map(line -> new Result.LineResult(line.lineNo(), line.sku(), line.category(), line.quantity(),
                        line.amount(), line.earnEligible(), line.returnedQuantity(), line.returnedAmount()))
                .toList();
        List<Result.ReturnResult> returns = orders.returns(order.id()).stream()
                .map(taken -> new Result.ReturnResult(
                        taken.returnRef(), taken.occurredAt(), taken.pointsClawedBack(), taken.debtAdded()))
                .toList();
        long available = ledger.liveBalance(member.id(), now) - member.debt();
        return new Result(order.orderRef(), member.externalRef(), order.currency(), order.occurredAt(),
                order.storeCode(), order.channel(), order.grantedPoints(), order.spendAmount(), available,
                member.debt(), lines, returns, replayed);
    }

    /** The lines as the order first records them: nothing returned yet, and a missing eligibility flag means yes. */
    private static List<OrderLine> sold(List<Command.Line> lines) {
        if (lines == null || lines.isEmpty()) {
            throw PointsRefusal.invalid("LINE_LIMIT_EXCEEDED", "An order has at least one line.");
        }
        if (lines.size() > LINE_LIMIT) {
            throw PointsRefusal.invalid(
                    "LINE_LIMIT_EXCEEDED", "An order has at most " + LINE_LIMIT + " lines.");
        }
        Set<Integer> seen = new HashSet<>();
        List<OrderLine> sold = new ArrayList<>(lines.size());
        for (Command.Line line : lines) {
            if (line == null || !seen.add(line.lineNo())) {
                throw PointsRefusal.invalid("Every order line carries its own lineNo.");
            }
            sold.add(OrderLine.sold(
                    line.lineNo(),
                    text(line.sku(), 100),
                    text(line.category(), 100),
                    line.quantity(),
                    line.amount(),
                    line.earnEligible() == null || line.earnEligible()));
        }
        return List.copyOf(sold);
    }

    private static void requireNotInTheFuture(Instant occurredAt, Instant now) {
        if (occurredAt.isAfter(now.plus(FUTURE_TOLERANCE))) {
            throw PointsRefusal.invalid("occurredAt is at most five minutes in the future.");
        }
    }

    private static String currency(String raw, ProgramView program) {
        String value = raw == null || raw.isBlank() ? program.currency() : raw.strip();
        if (!CURRENCY.matcher(value).matches()) {
            throw PointsRefusal.invalid("currency is an ISO 4217 code of three upper-case letters.");
        }
        if (!value.equals(program.currency())) {
            throw PointsRefusal.invalid(
                    "CURRENCY_MISMATCH", "The order's currency is not the program's, so nothing is granted.");
        }
        return value;
    }

    private static String text(String raw, int max) {
        if (raw == null) {
            return null;
        }
        String value = raw.strip();
        if (value.isEmpty()) {
            return null;
        }
        if (value.length() > max) {
            throw PointsRefusal.invalid("A text field of the order has at most " + max + " characters.");
        }
        return value;
    }

    private static List<Object> canonical(List<OrderLine> lines) {
        List<Object> parts = new ArrayList<>(lines.size());
        for (OrderLine line : lines) {
            parts.add(List.of(line.lineNo(), String.valueOf(line.sku()), String.valueOf(line.category()),
                    line.quantity().toPlainString(), line.amount(), line.earnEligible()));
        }
        return parts;
    }
}
