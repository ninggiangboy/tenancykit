package dev.ngb.mailroom.audience.adapter.suppression.persistence;

import static dev.ngb.mailroom_jooq.Tables.SUPPRESSIONS;

import dev.ngb.core.id.TenantId;
import dev.ngb.mailroom.audience.application.suppression.port.SuppressionRepository;
import dev.ngb.mailroom.audience.domain.suppression.Suppression;
import dev.ngb.mailroom.audience.domain.suppression.SuppressionReason;
import dev.ngb.mailroom.audience.domain.suppression.SuppressionScope;
import java.util.Optional;
import org.jooq.DSLContext;

/** The suppression store. An ALL block is never lowered to MARKETING by a later unsubscribe. */
public class JooqSuppressionRepository implements SuppressionRepository {

    private final DSLContext dsl;

    public JooqSuppressionRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    @Override
    public Optional<Suppression> find(String normalizedAddress) {
        return dsl.selectFrom(SUPPRESSIONS)
                .where(SUPPRESSIONS.EMAIL_NORMALIZED.eq(normalizedAddress))
                .fetchOptional(row -> new Suppression(
                        new TenantId(row.getTenantId()),
                        row.getEmailNormalized(),
                        SuppressionScope.valueOf(row.getScope()),
                        SuppressionReason.valueOf(row.getReason()),
                        row.getCreatedAt()));
    }

    @Override
    public void save(Suppression suppression) {
        Optional<Suppression> existing = find(suppression.emailNormalized());
        if (existing.isPresent()
                && existing.get().scope() == SuppressionScope.ALL
                && suppression.scope() != SuppressionScope.ALL) {
            return;
        }
        dsl.insertInto(SUPPRESSIONS)
                .set(SUPPRESSIONS.TENANT_ID, suppression.tenantId().value())
                .set(SUPPRESSIONS.EMAIL_NORMALIZED, suppression.emailNormalized())
                .set(SUPPRESSIONS.SCOPE, suppression.scope().name())
                .set(SUPPRESSIONS.REASON, suppression.reason().name())
                .set(SUPPRESSIONS.CREATED_AT, suppression.createdAt())
                .onConflict(SUPPRESSIONS.TENANT_ID, SUPPRESSIONS.EMAIL_NORMALIZED)
                .doUpdate()
                .set(SUPPRESSIONS.SCOPE, suppression.scope().name())
                .set(SUPPRESSIONS.REASON, suppression.reason().name())
                .execute();
    }

    @Override
    public boolean delete(String normalizedAddress) {
        return dsl.deleteFrom(SUPPRESSIONS)
                        .where(SUPPRESSIONS.EMAIL_NORMALIZED.eq(normalizedAddress))
                        .execute()
                == 1;
    }
}
