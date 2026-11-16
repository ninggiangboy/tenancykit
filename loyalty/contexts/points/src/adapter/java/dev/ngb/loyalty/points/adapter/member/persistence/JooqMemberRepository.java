package dev.ngb.loyalty.points.adapter.member.persistence;

import static dev.ngb.loyalty_jooq.Tables.MEMBERS;

import dev.ngb.core.id.TenantId;
import dev.ngb.core.tenancy.TenantContext;
import dev.ngb.loyalty.points.application.member.port.MemberRepository;
import dev.ngb.loyalty.points.application.member.port.MemberRow;
import dev.ngb.loyalty.points.domain.member.MemberStatus;
import dev.ngb.loyalty_jooq.tables.records.MembersRecord;
import java.util.Optional;
import java.util.UUID;
import org.jooq.DSLContext;

/**
 * The member rows this context writes.
 *
 * <p>The reference is the handle a caller knows, so both the write and the lock go through it; the id is loyalty's own
 * and appears in every event. `on conflict do nothing` is what makes two first orders for the same new member enrol one
 * member: the loser of the race inserts nothing and locks the winner's row instead.
 */
public class JooqMemberRepository implements MemberRepository {

    private final DSLContext dsl;
    private final TenantContext tenantContext;

    public JooqMemberRepository(DSLContext dsl, TenantContext tenantContext) {
        this.dsl = dsl;
        this.tenantContext = tenantContext;
    }

    @Override
    public boolean addIfAbsent(MemberRow member) {
        return dsl.insertInto(MEMBERS)
                        .set(MEMBERS.TENANT_ID, tenantId().value())
                        .set(MEMBERS.ID, member.id())
                        .set(MEMBERS.EXTERNAL_REF, member.externalRef())
                        .set(MEMBERS.DISPLAY_NAME, member.displayName())
                        .set(MEMBERS.TIER_CODE, member.tierCode())
                        .set(MEMBERS.DEBT, member.debt())
                        .set(MEMBERS.STATUS, member.status().name())
                        .set(MEMBERS.CREATED_AT, member.enrolledAt())
                        .set(MEMBERS.VERSION, member.version())
                        .onConflict(MEMBERS.TENANT_ID, MEMBERS.EXTERNAL_REF)
                        .doNothing()
                        .execute()
                == 1;
    }

    @Override
    public Optional<MemberRow> lockByRef(String externalRef) {
        return dsl.selectFrom(MEMBERS)
                .where(MEMBERS.EXTERNAL_REF.eq(externalRef))
                .and(MEMBERS.STATUS.eq(MemberStatus.ACTIVE.name()))
                .forUpdate()
                .fetchOptional(JooqMemberRepository::toRow);
    }

    @Override
    public Optional<MemberRow> lockById(java.util.UUID memberId) {
        return dsl.selectFrom(MEMBERS)
                .where(MEMBERS.ID.eq(memberId))
                .forUpdate()
                .fetchOptional(JooqMemberRepository::toRow);
    }

    @Override
    public void update(MemberRow member) {
        int updated = dsl.update(MEMBERS)
                .set(MEMBERS.TIER_CODE, member.tierCode())
                .set(MEMBERS.DEBT, member.debt())
                .set(MEMBERS.DISPLAY_NAME, member.displayName())
                .set(MEMBERS.VERSION, member.version() + 1)
                .where(MEMBERS.ID.eq(member.id()))
                .and(MEMBERS.TENANT_ID.eq(tenantId().value()))
                .execute();
        if (updated != 1) {
            throw new IllegalStateException("member " + member.id() + " was not updated");
        }
    }

    @Override
    public void markErased(UUID memberId) {
        int updated = dsl.update(MEMBERS)
                .set(MEMBERS.STATUS, MemberStatus.ERASED.name())
                .set(MEMBERS.EXTERNAL_REF, (String) null)
                .set(MEMBERS.DISPLAY_NAME, (String) null)
                .set(MEMBERS.VERSION, MEMBERS.VERSION.plus(1))
                .where(MEMBERS.ID.eq(memberId))
                .and(MEMBERS.TENANT_ID.eq(tenantId().value()))
                .and(MEMBERS.STATUS.eq(MemberStatus.ACTIVE.name()))
                .execute();
        if (updated != 1) {
            throw new IllegalStateException("member " + memberId + " was not erased");
        }
    }

    private TenantId tenantId() {
        return tenantContext.requireTenant();
    }

    private static MemberRow toRow(MembersRecord row) {
        return new MemberRow(
                row.getId(),
                row.getExternalRef(),
                row.getDisplayName(),
                row.getTierCode(),
                row.getDebt(),
                MemberStatus.valueOf(row.getStatus()),
                row.getCreatedAt(),
                row.getVersion());
    }
}
