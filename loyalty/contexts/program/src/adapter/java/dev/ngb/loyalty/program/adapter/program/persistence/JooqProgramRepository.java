package dev.ngb.loyalty.program.adapter.program.persistence;

import static dev.ngb.loyalty_jooq.Tables.LOYALTY_PROGRAMS;

import dev.ngb.core.id.TenantId;
import dev.ngb.loyalty.program.application.program.port.ProgramRepository;
import dev.ngb.loyalty.program.domain.program.Program;
import dev.ngb.loyalty_jooq.tables.records.LoyaltyProgramsRecord;
import java.time.ZoneId;
import java.util.Optional;
import org.jooq.DSLContext;

public class JooqProgramRepository implements ProgramRepository {

    private final DSLContext dsl;

    public JooqProgramRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    @Override
    public void add(Program p) {
        dsl.insertInto(LOYALTY_PROGRAMS)
                .set(LOYALTY_PROGRAMS.TENANT_ID, p.tenantId().value())
                .set(LOYALTY_PROGRAMS.CURRENCY, p.currency())
                .set(LOYALTY_PROGRAMS.UNIT_AMOUNT, p.unitAmount())
                .set(LOYALTY_PROGRAMS.EXPIRY_MONTHS, p.expiryMonths())
                .set(LOYALTY_PROGRAMS.EXPIRY_NOTICE_DAYS, p.expiryNoticeDays())
                .set(LOYALTY_PROGRAMS.TIER_WINDOW_MONTHS, p.tierWindowMonths())
                .set(LOYALTY_PROGRAMS.TIME_ZONE, p.timeZone().getId())
                .set(LOYALTY_PROGRAMS.TIERS_DIRTY, p.tiersDirty())
                .set(LOYALTY_PROGRAMS.VERSION, p.version())
                .set(LOYALTY_PROGRAMS.CREATED_AT, p.createdAt())
                .set(LOYALTY_PROGRAMS.UPDATED_AT, p.updatedAt())
                .execute();
    }

    @Override
    public void update(Program p) {
        int updated = dsl.update(LOYALTY_PROGRAMS)
                .set(LOYALTY_PROGRAMS.CURRENCY, p.currency())
                .set(LOYALTY_PROGRAMS.UNIT_AMOUNT, p.unitAmount())
                .set(LOYALTY_PROGRAMS.EXPIRY_MONTHS, p.expiryMonths())
                .set(LOYALTY_PROGRAMS.EXPIRY_NOTICE_DAYS, p.expiryNoticeDays())
                .set(LOYALTY_PROGRAMS.TIER_WINDOW_MONTHS, p.tierWindowMonths())
                .set(LOYALTY_PROGRAMS.TIME_ZONE, p.timeZone().getId())
                .set(LOYALTY_PROGRAMS.TIERS_DIRTY, p.tiersDirty())
                .set(LOYALTY_PROGRAMS.UPDATED_AT, p.updatedAt())
                .set(LOYALTY_PROGRAMS.VERSION, p.version())
                .where(LOYALTY_PROGRAMS.TENANT_ID.eq(p.tenantId().value()))
                .and(LOYALTY_PROGRAMS.VERSION.lt(p.version()))
                .execute();
        if (updated != 1) {
            throw new IllegalStateException(
                    "program " + p.tenantId() + " was not updated: version " + p.version() + " is not newer");
        }
    }

    @Override
    public Optional<Program> lock(TenantId tenantId) {
        return dsl.selectFrom(LOYALTY_PROGRAMS)
                .where(LOYALTY_PROGRAMS.TENANT_ID.eq(tenantId.value()))
                .forNoKeyUpdate()
                .fetchOptional(JooqProgramRepository::toDomain);
    }

    private static Program toDomain(LoyaltyProgramsRecord row) {
        return Program.restore(
                new TenantId(row.getTenantId()),
                row.getCurrency(),
                row.getUnitAmount(),
                row.getExpiryMonths(),
                row.getExpiryNoticeDays(),
                row.getTierWindowMonths(),
                ZoneId.of(row.getTimeZone()),
                Boolean.TRUE.equals(row.getTiersDirty()),
                row.getCreatedAt(),
                row.getUpdatedAt(),
                row.getVersion());
    }
}
