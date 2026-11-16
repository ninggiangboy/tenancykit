package dev.ngb.loyalty.program.application.tier.command;

import dev.ngb.core.id.TenantId;
import dev.ngb.core.runtime.Clock;
import dev.ngb.core.runtime.IdGenerator;
import dev.ngb.core.runtime.UnitOfWork;
import dev.ngb.core.tenancy.TenantContext;
import dev.ngb.core.version.ExpectedVersion;
import dev.ngb.loyalty.program.application.program.exception.ProgramRefusal;
import dev.ngb.loyalty.program.application.program.port.ProgramRepository;
import dev.ngb.loyalty.program.application.tier.port.MemberTierPort;
import dev.ngb.loyalty.program.application.tier.port.TierRepository;
import dev.ngb.loyalty.program.application.tier.service.TierRules;
import dev.ngb.loyalty.program.domain.program.Program;
import dev.ngb.loyalty.program.domain.tier.Tier;
import dev.ngb.loyalty.program.domain.tier.TierId;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Replaces the whole ordered tier list by code. An existing code keeps its id, a new code gets one and a removed code is
 * allowed only while no member holds it.
 *
 * <p>Any change marks the tier list dirty on the program, so every member's tier is computed again by the evaluation
 * run; the program's version moves with it, and that version is the list's ETag.
 */
public class ReplaceTiersUseCase {

    public record Command(List<TierRules.Input> tiers) {}

    /** The stored list after the replace, with the program version a caller uses as the next {@code If-Match}. */
    public record Result(List<Tier> tiers, long version) {}

    private final UnitOfWork unitOfWork;
    private final ProgramRepository programs;
    private final TierRepository tiers;
    private final MemberTierPort memberTiers;
    private final TenantContext tenantContext;
    private final IdGenerator ids;
    private final Clock clock;

    public ReplaceTiersUseCase(
            UnitOfWork unitOfWork,
            ProgramRepository programs,
            TierRepository tiers,
            MemberTierPort memberTiers,
            TenantContext tenantContext,
            IdGenerator ids,
            Clock clock) {
        this.unitOfWork = unitOfWork;
        this.programs = programs;
        this.tiers = tiers;
        this.memberTiers = memberTiers;
        this.tenantContext = tenantContext;
        this.ids = ids;
        this.clock = clock;
    }

    public Result execute(Command command, ExpectedVersion expected) {
        List<TierRules.Entry> requested = TierRules.validated(command == null ? null : command.tiers());
        return unitOfWork.transactional(() -> {
            TenantId tenantId = tenantContext.requireTenant();
            Instant now = clock.now();
            Program program = programs.lock(tenantId)
                    .orElseThrow(() -> ProgramRefusal.notFound(
                            "PROGRAM_NOT_CONFIGURED", "No points program is configured for this tenant yet."));
            expected.check(program.version());

            Map<String, Tier> stored = new LinkedHashMap<>();
            for (Tier tier : tiers.findAll()) {
                stored.put(tier.code(), tier);
            }

            List<Tier> next = new ArrayList<>(requested.size());
            for (TierRules.Entry entry : requested) {
                Tier existing = stored.get(entry.code());
                next.add(existing == null
                        ? new Tier(tenantId, new TierId(ids.newId()), entry.code(), entry.name(), entry.minSpend(),
                                entry.earnMultiplier(), entry.position())
                        : existing.moveTo(entry.name(), entry.minSpend(), entry.earnMultiplier(), entry.position()));
            }
            Set<String> kept = Set.copyOf(stored.keySet());
            for (String code : kept) {
                if (requested.stream().noneMatch(entry -> entry.code().equals(code)) && memberTiers.isHeld(code)) {
                    throw ProgramRefusal.conflict(
                            "TIER_IN_USE", "The tier " + code + " is still held by a member and cannot leave the list.");
                }
            }

            boolean changed = !unchanged(stored, next);
            if (changed) {
                write(stored, next);
                program.markTiersDirty(now);
                programs.update(program);
            }
            return new Result(List.copyOf(next), program.version());
        });
    }

    private void write(Map<String, Tier> stored, List<Tier> next) {
        for (Tier tier : next) {
            if (!stored.containsKey(tier.code())) {
                tiers.add(tier);
            } else if (!same(stored.get(tier.code()), tier)) {
                tiers.update(tier);
            }
        }
        for (Tier tier : stored.values()) {
            if (next.stream().noneMatch(replacement -> replacement.code().equals(tier.code()))) {
                tiers.delete(tier.id());
            }
        }
    }

    private static boolean unchanged(Map<String, Tier> stored, List<Tier> next) {
        if (stored.size() != next.size()) {
            return false;
        }
        for (Tier tier : next) {
            Tier before = stored.get(tier.code());
            if (before == null || !same(before, tier)) {
                return false;
            }
        }
        return true;
    }

    private static boolean same(Tier left, Tier right) {
        return left.id().equals(right.id())
                && left.name().equals(right.name())
                && left.minSpend() == right.minSpend()
                && left.position() == right.position()
                && left.earnMultiplier().compareTo(right.earnMultiplier()) == 0;
    }
}
