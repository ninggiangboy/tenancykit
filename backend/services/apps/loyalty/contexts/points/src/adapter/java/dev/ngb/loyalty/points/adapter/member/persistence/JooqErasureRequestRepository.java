package dev.ngb.loyalty.points.adapter.member.persistence;

import static dev.ngb.loyalty_jooq.Tables.ERASURE_REQUESTS;

import dev.ngb.core.id.TenantId;
import dev.ngb.loyalty.points.application.member.port.ErasureRequestRepository;
import dev.ngb.loyalty.points.domain.member.ErasureRequest;
import java.util.Optional;
import org.jooq.DSLContext;

/**
 * Stores the record of an erasure, keyed by the hash of the reference the caller sent. The reference itself never reaches
 * this table: what is stored is what a retry needs to find its own answer and nothing a person can be found by.
 *
 * <p>The primary key of the table is the pair of tenant and hash, so a retry that slipped past the lookup inserts nothing
 * instead of appending a second record of the same request.
 */
public class JooqErasureRequestRepository implements ErasureRequestRepository {

    private final DSLContext dsl;

    public JooqErasureRequestRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    @Override
    public Optional<ErasureRequest> findByRefHash(String refHash) {
        return dsl.selectFrom(ERASURE_REQUESTS)
                .where(ERASURE_REQUESTS.REF_HASH.eq(refHash))
                .fetchOptional(row -> new ErasureRequest(
                        new TenantId(row.getTenantId()),
                        row.getRefHash(),
                        row.getMemberId(),
                        row.getErasedAt()));
    }

    @Override
    public boolean insertIfAbsent(ErasureRequest request) {
        return dsl.insertInto(ERASURE_REQUESTS)
                        .set(ERASURE_REQUESTS.TENANT_ID, request.tenantId().value())
                        .set(ERASURE_REQUESTS.REF_HASH, request.refHash())
                        .set(ERASURE_REQUESTS.MEMBER_ID, request.memberId())
                        .set(ERASURE_REQUESTS.ERASED_AT, request.erasedAt())
                        .onConflict(ERASURE_REQUESTS.TENANT_ID, ERASURE_REQUESTS.REF_HASH)
                        .doNothing()
                        .execute()
                == 1;
    }
}
