package dev.ngb.platform.integration.adapter.credential.persistence;

import static dev.ngb.platform_jooq.Tables.API_CREDENTIALS;

import dev.ngb.core.query.ListQuery;
import dev.ngb.core.query.Page;
import dev.ngb.core.spring.query.JooqLists;
import dev.ngb.core.spring.query.ListBinding;
import dev.ngb.platform.integration.application.credential.query.CredentialListing;
import dev.ngb.platform.integration.application.credential.query.CredentialReadRepository;
import dev.ngb.platform.integration.application.credential.query.CredentialView;
import dev.ngb.platform.integration.domain.credential.CredentialStatus;
import dev.ngb.platform_jooq.tables.records.ApiCredentialsRecord;
import java.util.Arrays;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.jooq.DSLContext;

/**
 * Selects credential metadata straight into views. The row is read whole, as every listing under the contract reads
 * its table, and {@link #toView} drops the secret digest: no view, and so no response, ever carries the hash.
 */
public class JooqCredentialReadRepository implements CredentialReadRepository {

    /** The fields a caller may filter and sort credentials on, bound to their columns; every value is a bind variable. */
    private static final ListBinding BINDING = ListBinding.of(CredentialListing.SCHEMA)
            .column("id", API_CREDENTIALS.ID)
            .column("key_id", API_CREDENTIALS.KEY_ID)
            .column("label", API_CREDENTIALS.LABEL)
            .column("status", API_CREDENTIALS.STATUS)
            .column("created_at", API_CREDENTIALS.CREATED_AT)
            .column("expires_at", API_CREDENTIALS.EXPIRES_AT)
            .column("revoked_at", API_CREDENTIALS.REVOKED_AT)
            .column("version", API_CREDENTIALS.VERSION)
            .build();

    private final DSLContext dsl;

    /** @return the binding of the credential listing, for the test that checks every sortable field has an index */
    public static ListBinding listBinding() {
        return BINDING;
    }

    public JooqCredentialReadRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    @Override
    public Page<CredentialView> list(ListQuery query, UUID tenantSystemId) {
        return JooqLists.fetch(dsl, API_CREDENTIALS, API_CREDENTIALS.TENANT_SYSTEM_ID.eq(tenantSystemId), BINDING, query,
                JooqCredentialReadRepository::toView);
    }

    private static CredentialView toView(ApiCredentialsRecord row) {
        return new CredentialView(
                row.getId(),
                row.getTenantSystemId(),
                row.getKeyId(),
                row.getLabel(),
                scopes(row.getScopes()),
                CredentialStatus.valueOf(row.getStatus()),
                row.getCreatedAt(),
                row.getExpiresAt(),
                row.getRevokedAt());
    }

    private static Set<String> scopes(String[] codes) {
        return codes == null ? Set.of() : Arrays.stream(codes).collect(Collectors.toUnmodifiableSet());
    }
}
