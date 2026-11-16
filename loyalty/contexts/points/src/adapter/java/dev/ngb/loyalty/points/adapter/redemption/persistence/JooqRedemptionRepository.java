package dev.ngb.loyalty.points.adapter.redemption.persistence;

import static dev.ngb.loyalty_jooq.Tables.REDEMPTIONS;

import dev.ngb.core.id.TenantId;
import dev.ngb.core.tenancy.TenantContext;
import dev.ngb.loyalty.points.application.redemption.port.RedemptionRepository;
import dev.ngb.loyalty.points.application.redemption.port.RedemptionRow;
import dev.ngb.loyalty.points.domain.redemption.RedemptionStatus;
import dev.ngb.loyalty_jooq.tables.records.RedemptionsRecord;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.jooq.DSLContext;

/**
 * The redemptions a member made.
 *
 * <p>`on conflict do nothing` on the tenant and the idempotency key is what makes one key spend once: the caller looks
 * the key up after it holds the member's lock, so a second call carrying the same key reads what the first committed,
 * and the insert only loses a race when two different members used the key at the same moment.
 */
public class JooqRedemptionRepository implements RedemptionRepository {

    private final DSLContext dsl;
    private final TenantContext tenantContext;

    public JooqRedemptionRepository(DSLContext dsl, TenantContext tenantContext) {
        this.dsl = dsl;
        this.tenantContext = tenantContext;
    }

    @Override
    public boolean add(RedemptionRow redemption) {
        return dsl.insertInto(REDEMPTIONS)
                        .set(REDEMPTIONS.TENANT_ID, tenantId().value())
                        .set(REDEMPTIONS.ID, redemption.id())
                        .set(REDEMPTIONS.MEMBER_ID, redemption.memberId())
                        .set(REDEMPTIONS.IDEMPOTENCY_KEY, redemption.idempotencyKey())
                        .set(REDEMPTIONS.FINGERPRINT, redemption.fingerprint())
                        .set(REDEMPTIONS.POINTS, redemption.points())
                        .set(REDEMPTIONS.REFERENCE, redemption.reference())
                        .set(REDEMPTIONS.STATUS, redemption.status().name())
                        .set(REDEMPTIONS.CREATED_AT, redemption.createdAt())
                        .set(REDEMPTIONS.REVERSED_AT, redemption.reversedAt())
                        .onConflict(REDEMPTIONS.TENANT_ID, REDEMPTIONS.IDEMPOTENCY_KEY)
                        .doNothing()
                        .execute()
                == 1;
    }

    @Override
    public Optional<RedemptionRow> findByIdempotencyKey(String idempotencyKey) {
        return dsl.selectFrom(REDEMPTIONS)
                .where(REDEMPTIONS.IDEMPOTENCY_KEY.eq(idempotencyKey))
                .fetchOptional(JooqRedemptionRepository::toRow);
    }

    @Override
    public Optional<RedemptionRow> lockById(UUID id) {
        return dsl.selectFrom(REDEMPTIONS)
                .where(REDEMPTIONS.ID.eq(id))
                .forUpdate()
                .fetchOptional(JooqRedemptionRepository::toRow);
    }

    @Override
    public void markReversed(UUID id, Instant reversedAt) {
        int updated = dsl.update(REDEMPTIONS)
                .set(REDEMPTIONS.STATUS, RedemptionStatus.REVERSED.name())
                .set(REDEMPTIONS.REVERSED_AT, reversedAt)
                .where(REDEMPTIONS.ID.eq(id))
                .and(REDEMPTIONS.TENANT_ID.eq(tenantId().value()))
                .and(REDEMPTIONS.STATUS.eq(RedemptionStatus.ACTIVE.name()))
                .execute();
        if (updated != 1) {
            throw new IllegalStateException("redemption " + id + " was not marked reversed");
        }
    }

    private TenantId tenantId() {
        return tenantContext.requireTenant();
    }

    private static RedemptionRow toRow(RedemptionsRecord row) {
        return new RedemptionRow(
                row.getId(),
                row.getMemberId(),
                row.getIdempotencyKey(),
                row.getFingerprint(),
                row.getPoints(),
                row.getReference(),
                RedemptionStatus.valueOf(row.getStatus()),
                row.getCreatedAt(),
                row.getReversedAt());
    }
}
