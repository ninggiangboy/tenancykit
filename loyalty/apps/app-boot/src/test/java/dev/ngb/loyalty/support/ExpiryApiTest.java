package dev.ngb.loyalty.support;

import dev.ngb.core.id.TenantId;
import dev.ngb.loyalty.points.application.expiry.command.ExpirePointsUseCase;
import dev.ngb.loyalty.points.application.expiry.command.NoticeExpiringPointsUseCase;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * The two scheduled runs and the lot state they read, on top of the HTTP harness.
 *
 * <p>A test cannot wait for a lot to expire, so it seeds one: an EARN entry and the lot that entry granted, with its
 * expiry where the case needs it. That is exactly the state a real earn leaves behind, which is why the runs behave the
 * same on a seeded lot as on one an order created.
 */
public abstract class ExpiryApiTest extends PointsApiTest {

    @Autowired protected ExpirePointsUseCase expirePoints;
    @Autowired protected NoticeExpiringPointsUseCase noticeExpiring;

    protected ExpirePointsUseCase.Result runExpiry(TenantId tenant) {
        return inTenant(tenant, expirePoints::runOnce);
    }

    protected NoticeExpiringPointsUseCase.Result runNotice(TenantId tenant) {
        return inTenant(tenant, noticeExpiring::runOnce);
    }

    /** The member's id, enrolling it first when the case has not done so. */
    protected UUID memberId(TenantId tenant, String externalRef) {
        return inTenant(tenant, () -> unitOfWork.transactional(() -> {
            dsl.execute(
                    "insert into members (tenant_id, id, external_ref, status, created_at) "
                            + "values (?, ?, ?, 'ACTIVE', now()) on conflict (tenant_id, external_ref) do nothing",
                    tenant.value(), UUID.randomUUID(), externalRef);
            return dsl.fetchOne("select id from members where external_ref = ?", externalRef)
                    .get(0, UUID.class);
        }));
    }

    /** A lot granted by an earn, still holding all of it. */
    protected UUID seedLot(TenantId tenant, String externalRef, long points, Instant expiresAt) {
        return seedLot(tenant, externalRef, points, points, expiresAt);
    }

    /** A lot with part or all of it already spent, so a case can state what a spent lot does. */
    protected UUID seedLot(TenantId tenant, String externalRef, long earned, long remaining, Instant expiresAt) {
        UUID member = memberId(tenant, externalRef);
        UUID entryId = UUID.randomUUID();
        UUID lotId = UUID.randomUUID();
        Timestamp expiry = Timestamp.from(expiresAt);
        Timestamp earnedAt = Timestamp.from(expiresAt.minus(365, ChronoUnit.DAYS));
        inTenant(tenant, () -> unitOfWork.transactional(() -> {
            dsl.execute(
                    "insert into ledger_entries (id, tenant_id, member_id, kind, points, created_at) "
                            + "values (?, ?, ?, 'EARN', ?, now())",
                    entryId, tenant.value(), member, earned);
            dsl.execute(
                    "insert into point_lots (tenant_id, id, member_id, entry_id, earned, remaining, earned_at, "
                            + "expires_at) values (?, ?, ?, ?, ?, ?, ?, ?)",
                    tenant.value(), lotId, member, entryId, earned, remaining, earnedAt, expiry);
            return null;
        }));
        return lotId;
    }

    /** How many of the member's lots the notice run has announced. */
    protected long noticedLots(TenantId tenant, String externalRef) {
        return scalar(
                tenant,
                "select count(*) from point_lots where expiry_noticed_at is not null and member_id = "
                        + "(select id from members where external_ref = ?)",
                externalRef);
    }
}
