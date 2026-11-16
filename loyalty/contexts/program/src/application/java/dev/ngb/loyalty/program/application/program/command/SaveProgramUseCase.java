package dev.ngb.loyalty.program.application.program.command;

import dev.ngb.core.id.TenantId;
import dev.ngb.core.runtime.Clock;
import dev.ngb.core.runtime.UnitOfWork;
import dev.ngb.core.tenancy.TenantContext;
import dev.ngb.core.version.ExpectedVersion;
import dev.ngb.loyalty.program.application.program.exception.ProgramRefusal;
import dev.ngb.loyalty.program.application.program.port.LedgerEntryPort;
import dev.ngb.loyalty.program.application.program.port.ProgramRepository;
import dev.ngb.loyalty.program.application.program.service.ProgramRules;
import dev.ngb.loyalty.program.domain.program.Program;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Optional;

/**
 * Creates the tenant's one program, or replaces it. The first call is a create and needs {@code If-Match: *}; every later
 * one is a replace and needs the ETag it read.
 *
 * <p>The currency is frozen once the ledger holds an entry: a point already earned was worth something, and changing the
 * currency underneath it would change what it is worth.
 */
public class SaveProgramUseCase {

    public record Command(
            String currency,
            Long unitAmount,
            Integer expiryMonths,
            Integer expiryNoticeDays,
            Integer tierWindowMonths,
            String timeZone) {}

    private final UnitOfWork unitOfWork;
    private final ProgramRepository programs;
    private final LedgerEntryPort ledgerEntries;
    private final TenantContext tenantContext;
    private final Clock clock;

    public SaveProgramUseCase(
            UnitOfWork unitOfWork,
            ProgramRepository programs,
            LedgerEntryPort ledgerEntries,
            TenantContext tenantContext,
            Clock clock) {
        this.unitOfWork = unitOfWork;
        this.programs = programs;
        this.ledgerEntries = ledgerEntries;
        this.tenantContext = tenantContext;
        this.clock = clock;
    }

    public Program execute(Command command, ExpectedVersion expected) {
        String currency = ProgramRules.currency(command.currency());
        long unitAmount = ProgramRules.unitAmount(command.unitAmount());
        int expiryMonths = ProgramRules.expiryMonths(command.expiryMonths());
        int expiryNoticeDays = ProgramRules.expiryNoticeDays(command.expiryNoticeDays());
        int tierWindowMonths = ProgramRules.tierWindowMonths(command.tierWindowMonths());
        ZoneId timeZone = ProgramRules.timeZone(command.timeZone());
        return unitOfWork.transactional(() -> {
            TenantId tenantId = tenantContext.requireTenant();
            Instant now = clock.now();
            Optional<Program> stored = programs.lock(tenantId);
            if (stored.isEmpty()) {
                expected.checkAbsent();
                Program created = Program.create(
                        tenantId, currency, unitAmount, expiryMonths, expiryNoticeDays, tierWindowMonths, timeZone, now);
                programs.add(created);
                return created;
            }
            Program program = stored.get();
            expected.check(program.version());
            if (!program.currency().equals(currency) && ledgerEntries.hasEntries()) {
                throw ProgramRefusal.conflict(
                        "CURRENCY_IMMUTABLE", "The currency cannot change once an entry exists in the points ledger.");
            }
            program.change(currency, unitAmount, expiryMonths, expiryNoticeDays, tierWindowMonths, timeZone, now);
            programs.update(program);
            return program;
        });
    }
}
