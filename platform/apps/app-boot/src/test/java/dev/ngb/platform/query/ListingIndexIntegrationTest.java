package dev.ngb.platform.query;

import static org.assertj.core.api.Assertions.assertThat;

import dev.ngb.core.query.FieldSpec;
import dev.ngb.core.query.FieldType;
import dev.ngb.core.query.FilterSchema;
import dev.ngb.core.query.Op;
import dev.ngb.core.spring.query.ListBinding;
import dev.ngb.core.spring.query.ListingIndexCoverage;
import dev.ngb.platform.Application;
import dev.ngb.platform.access.adapter.role.persistence.JooqRoleReadRepository;
import dev.ngb.platform.access.adapter.user.persistence.JooqUserReadRepository;
import dev.ngb.platform.integration.adapter.credential.persistence.JooqCredentialReadRepository;
import dev.ngb.platform.integration.adapter.tenantsystem.persistence.JooqTenantSystemReadRepository;
import dev.ngb.platform.support.PlatformContainerConfig;
import dev.ngb.platform.tenancy.adapter.tenant.persistence.JooqTenantReadRepository;
import dev.ngb.platform_jooq.Tables;
import java.util.Map;
import org.jooq.DSLContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/**
 * Every listing sorts only on fields that lead an index of their table, so a sort a caller asks for never scans the
 * whole table, and searches a text field with contains only where a trigram index serves it. Runs against the migrated
 * schema.
 */
@SpringBootTest(classes = Application.class)
@Import(PlatformContainerConfig.class)
class ListingIndexIntegrationTest {

    @Autowired DSLContext dsl;

    @Test
    void everyListingIsCovered() {
        Map<String, ListBinding> listings = Map.of(
                "tenant", JooqTenantReadRepository.listBinding(),
                "user", JooqUserReadRepository.listBinding(),
                "role", JooqRoleReadRepository.listBinding(),
                "tenant system", JooqTenantSystemReadRepository.listBinding(),
                "credential", JooqCredentialReadRepository.listBinding());

        listings.forEach((name, binding) ->
                assertThat(ListingIndexCoverage.missing(dsl, binding)).as("the %s listing", name).isEmpty());
    }

    @Test
    void aSortableFieldWithoutAnIndexIsReported() {
        FilterSchema schema = FilterSchema.of("id", "id",
                FieldSpec.of("id", FieldType.UUID).asSortable(),
                FieldSpec.of("scope", FieldType.STRING).asSortable());
        ListBinding binding = ListBinding.of(schema).column("id", Tables.ROLES.ID).column("scope", Tables.ROLES.SCOPE).build();

        assertThat(ListingIndexCoverage.missing(dsl, binding))
                .singleElement()
                .asString()
                .contains("scope")
                .contains("roles");
    }

    @Test
    void aContainsFieldWithoutATrigramIndexIsReported() {
        FilterSchema schema = FilterSchema.of("id", "id",
                FieldSpec.of("id", FieldType.UUID).asSortable(),
                FieldSpec.of("display_name", FieldType.STRING).with(Op.CONTAINS));
        ListBinding binding = ListBinding.of(schema)
                .column("id", Tables.USERS.ID)
                .column("display_name", Tables.USERS.DISPLAY_NAME)
                .build();

        assertThat(ListingIndexCoverage.missing(dsl, binding))
                .singleElement()
                .asString()
                .contains("display_name")
                .contains("gin_trgm_ops");
    }
}
