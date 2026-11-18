package dev.ngb.loyalty.program.adapter.program.persistence;

import static dev.ngb.loyalty_jooq.Tables.LOYALTY_PROGRAMS;

import dev.ngb.loyalty.program.api.program.ProgramView;
import dev.ngb.loyalty.program.application.program.query.ProgramReadRepository;
import dev.ngb.loyalty_jooq.tables.records.LoyaltyProgramsRecord;
import java.time.ZoneId;
import java.util.Optional;
import org.jooq.DSLContext;

/** Selects the program straight into the view; the aggregate is never built on a read. */
public class JooqProgramReadRepository implements ProgramReadRepository {

    private final DSLContext dsl;

    public JooqProgramReadRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    @Override
    public Optional<ProgramView> find() {
        return dsl.selectFrom(LOYALTY_PROGRAMS).fetchOptional(JooqProgramReadRepository::toView);
    }

    private static ProgramView toView(LoyaltyProgramsRecord row) {
        return new ProgramView(
                row.getCurrency(),
                row.getUnitAmount(),
                row.getExpiryMonths(),
                row.getExpiryNoticeDays(),
                row.getTierWindowMonths(),
                ZoneId.of(row.getTimeZone()),
                row.getVersion());
    }
}
