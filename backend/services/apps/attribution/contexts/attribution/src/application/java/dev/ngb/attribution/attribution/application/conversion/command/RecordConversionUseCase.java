package dev.ngb.attribution.attribution.application.conversion.command;

import dev.ngb.attribution.attribution.application.conversion.port.ConversionRepository;
import dev.ngb.attribution.attribution.application.goal.port.GoalRepository;
import dev.ngb.attribution.attribution.domain.conversion.Conversion;
import dev.ngb.attribution.attribution.domain.conversion.ConversionSource;
import dev.ngb.attribution.attribution.domain.goal.Goal;
import dev.ngb.attribution.shared.redirect.ClickToken;
import dev.ngb.core.error.BadRequestException;
import dev.ngb.core.error.IdempotencyConflictException;
import dev.ngb.core.error.NotFoundException;
import dev.ngb.core.id.TenantId;
import dev.ngb.core.runtime.Clock;
import dev.ngb.core.runtime.IdGenerator;
import dev.ngb.core.runtime.UnitOfWork;
import dev.ngb.core.tenancy.TenantContext;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * The postback: a Tenant's backend reports that a goal was reached.
 *
 * <p>It verifies the click token with the deployment's click keys. A forged or foreign-tenant token is **not** rejected —
 * the conversion is stored as {@code UNATTRIBUTED} with the click cleared — because a Tenant's order flow must not fail
 * when its own reporting is imperfect. A missing click and a missing visitor is a genuine client error and is refused.
 *
 * <p>It is idempotent on {@code (tenant, Idempotency-Key)}: the same key and body returns the first conversion, and the
 * same key with a different body is refused rather than folding two orders into one.
 */
public class RecordConversionUseCase {

    /** What a postback produced: the stored conversion and whether this call created it. */
    public record Result(Conversion conversion, boolean created) {}

    public record Command(
            String goalKey,
            String clickToken,
            String visitorId,
            BigDecimal value,
            String currency,
            Instant occurredAt,
            String idempotencyKey) {}

    private final UnitOfWork unitOfWork;
    private final TenantContext tenantContext;
    private final ConversionRepository conversions;
    private final GoalRepository goals;
    private final Map<String, String> clickSecrets;
    private final IdGenerator ids;
    private final Clock clock;

    public RecordConversionUseCase(
            UnitOfWork unitOfWork,
            TenantContext tenantContext,
            ConversionRepository conversions,
            GoalRepository goals,
            Map<String, String> clickSecrets,
            IdGenerator ids,
            Clock clock) {
        this.unitOfWork = unitOfWork;
        this.tenantContext = tenantContext;
        this.conversions = conversions;
        this.goals = goals;
        this.clickSecrets = Map.copyOf(clickSecrets);
        this.ids = ids;
        this.clock = clock;
    }

    public Result execute(Command command) {
        if (command.idempotencyKey() == null || command.idempotencyKey().isBlank()) {
            throw new BadRequestException("A postback needs an Idempotency-Key.");
        }
        TenantId tenant = tenantContext.requireTenant();
        return unitOfWork.transactional(() -> {
            Optional<Conversion> replay = conversions.findByIdempotencyKey(command.idempotencyKey());
            if (replay.isPresent()) {
                // A replay with a different body is refused: the key names one order, not one endpoint call.
                Conversion stored = replay.get();
                if (!sameBody(stored, goals, command)) {
                    throw new IdempotencyConflictException("conversion.postback", command.idempotencyKey());
                }
                return new Result(stored, false);
            }
            Goal goal = goals.findByKey(command.goalKey())
                    .orElseThrow(() -> new NotFoundException("No goal has that key."));
            boolean namedClick = command.clickToken() != null && !command.clickToken().isBlank();
            UUID clickId = verifiedClick(command.clickToken()).orElse(null);
            boolean namedVisitor = command.visitorId() != null && !command.visitorId().isBlank();
            if (!namedClick && !namedVisitor) {
                // Neither a click nor a visitor: there is nothing to attribute to, and the caller forgot both.
                throw new BadRequestException("A postback names a click, a visitor or both.");
            }
            BigDecimal value = command.value() != null
                    ? command.value()
                    : goal.defaultValue() != null ? goal.defaultValue() : BigDecimal.ZERO;
            String currency = firstNonBlank(command.currency(), goal.currency(), "USD");
            Instant occurredAt = command.occurredAt() == null ? clock.now() : command.occurredAt();
            // A token that was sent but did not verify leaves clickId null and the conversion lands unattributed, which
            // is the plan's rule: a Tenant's order flow must not fail on its own reporting.
            Conversion conversion = Conversion.pending(
                    ids.newId(), tenant, goal.id(), clickId, namedVisitor ? command.visitorId() : null, value,
                    currency, occurredAt, ConversionSource.POSTBACK, command.idempotencyKey(), clock.now());
            conversions.insert(conversion);
            return new Result(conversion, true);
        });
    }

    /**
     * The click the token proves, or empty when the token is absent, malformed, names an unknown key or was tampered
     * with. An empty answer is not a failure: the conversion is stored unattributed rather than rejected.
     */
    private Optional<UUID> verifiedClick(String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        return ClickToken.verify(token, clickSecrets).map(ClickToken.Verified::clickId);
    }

    /**
     * Whether a replay names the same goal and value as the stored conversion, so the same key with a changed body can
     * be refused. The stored goal is a row id; the replay names its key, so the two are compared through the goal.
     */
    private static boolean sameBody(Conversion stored, GoalRepository goals, Command command) {
        if (command.goalKey() == null) {
            return false;
        }
        Optional<Goal> goal = goals.findByKey(command.goalKey());
        if (goal.isEmpty() || !goal.get().id().equals(stored.goalId())) {
            return false;
        }
        return command.value() == null || command.value().compareTo(stored.value()) == 0;
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }
}
