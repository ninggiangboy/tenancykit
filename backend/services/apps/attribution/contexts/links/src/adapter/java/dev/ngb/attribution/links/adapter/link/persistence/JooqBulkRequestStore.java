package dev.ngb.attribution.links.adapter.link.persistence;

import static dev.ngb.attribution_jooq.Tables.BULK_LINK_REQUESTS;

import dev.ngb.attribution.links.application.link.port.BulkRequestStore;
import dev.ngb.core.runtime.Clock;
import dev.ngb.core.tenancy.TenantContext;
import java.util.Optional;
import org.jooq.DSLContext;
import tools.jackson.databind.json.JsonMapper;

/** The bulk request ledger: one row per Idempotency-Key, holding the request hash and the result it produced. */
public class JooqBulkRequestStore implements BulkRequestStore {

    private final DSLContext dsl;
    private final JsonMapper json;
    private final TenantContext tenantContext;
    private final Clock clock;

    public JooqBulkRequestStore(DSLContext dsl, JsonMapper json, TenantContext tenantContext, Clock clock) {
        this.dsl = dsl;
        this.json = json;
        this.tenantContext = tenantContext;
        this.clock = clock;
    }

    @Override
    public Optional<BulkResult> find(String key) {
        return dsl.select(BULK_LINK_REQUESTS.RESULT)
                .from(BULK_LINK_REQUESTS)
                .where(BULK_LINK_REQUESTS.IDEMPOTENCY_KEY.eq(key))
                .fetchOptional(row -> json.readValue(row.value1().data(), BulkResult.class));
    }

    @Override
    public Optional<String> requestHash(String key) {
        return dsl.select(BULK_LINK_REQUESTS.REQUEST_HASH)
                .from(BULK_LINK_REQUESTS)
                .where(BULK_LINK_REQUESTS.IDEMPOTENCY_KEY.eq(key))
                .fetchOptional(BULK_LINK_REQUESTS.REQUEST_HASH);
    }

    @Override
    public void save(String key, String requestHash, BulkResult result) {
        dsl.insertInto(BULK_LINK_REQUESTS)
                .set(BULK_LINK_REQUESTS.TENANT_ID, tenantContext.requireTenant().value())
                .set(BULK_LINK_REQUESTS.IDEMPOTENCY_KEY, key)
                .set(BULK_LINK_REQUESTS.REQUEST_HASH, requestHash)
                .set(BULK_LINK_REQUESTS.RESULT, org.jooq.JSONB.valueOf(json.writeValueAsString(result)))
                .set(BULK_LINK_REQUESTS.CREATED_AT, clock.now())
                .onConflict(BULK_LINK_REQUESTS.TENANT_ID, BULK_LINK_REQUESTS.IDEMPOTENCY_KEY)
                .doNothing()
                .execute();
    }
}
