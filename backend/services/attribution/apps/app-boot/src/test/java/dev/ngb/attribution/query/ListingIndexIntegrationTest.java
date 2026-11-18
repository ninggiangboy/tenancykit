package dev.ngb.attribution.query;

import static org.assertj.core.api.Assertions.assertThat;

import dev.ngb.attribution.Application;
import dev.ngb.attribution.attribution.adapter.conversion.persistence.JooqConversionReadRepository;
import dev.ngb.attribution.attribution.adapter.goal.persistence.JooqGoalReadRepository;
import dev.ngb.attribution.links.adapter.campaign.persistence.JooqCampaignReadRepository;
import dev.ngb.attribution.links.adapter.link.persistence.JooqLinkReadRepository;
import dev.ngb.attribution.support.PostgresContainerConfig;
import dev.ngb.attribution.support.RabbitContainerConfig;
import dev.ngb.attribution_jooq.Tables;
import dev.ngb.core.query.FieldSpec;
import dev.ngb.core.query.FieldType;
import dev.ngb.core.query.FilterSchema;
import dev.ngb.core.query.Op;
import dev.ngb.core.spring.query.ListBinding;
import dev.ngb.core.spring.query.ListingIndexCoverage;
import java.util.Map;
import org.jooq.DSLContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Every listing sorts only on fields that lead an index of their table, so a sort a caller asks for never scans the
 * whole table, and every {@code contains} field has the trigram index such a search needs. Runs against the migrated
 * schema, which is the only place an index can be observed.
 */
@SpringBootTest(classes = Application.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("api")
class ListingIndexIntegrationTest {

    @Autowired DSLContext dsl;

    @DynamicPropertySource
    static void infrastructure(DynamicPropertyRegistry registry) {
        var postgres = PostgresContainerConfig.postgres();
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", () -> PostgresContainerConfig.APP_ROLE);
        registry.add("spring.datasource.password", () -> PostgresContainerConfig.APP_PASSWORD);
        // The api profile leaves migrations to the migrate step; this test needs the schema, so it migrates here.
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("spring.flyway.url", postgres::getJdbcUrl);
        registry.add("spring.flyway.user", postgres::getUsername);
        registry.add("spring.flyway.password", postgres::getPassword);
        registry.add("spring.flyway.placeholders.appDbPassword", () -> PostgresContainerConfig.APP_PASSWORD);
        var rabbit = RabbitContainerConfig.rabbit();
        registry.add("spring.rabbitmq.host", rabbit::getHost);
        registry.add("spring.rabbitmq.port", rabbit::getAmqpPort);
        registry.add("spring.rabbitmq.username", rabbit::getAdminUsername);
        registry.add("spring.rabbitmq.password", rabbit::getAdminPassword);
        registry.add("suite.replica.rebuild-on-start", () -> "false");
        registry.add("attribution.jobs.enabled", () -> "false");
        registry.add("attribution.housekeeping.enabled", () -> "false");
        registry.add("attribution.keys.visitor-hash-key", () -> "test-visitor-hash-secret");
        registry.add("attribution.keys.click-keys.k1", () -> "test-click-secret");
    }

    @Test
    void everyListingIsCovered() {
        Map<String, ListBinding> listings = Map.of(
                "campaign", JooqCampaignReadRepository.listBinding(),
                "link", JooqLinkReadRepository.listBinding(),
                "goal", JooqGoalReadRepository.listBinding(),
                "conversion", JooqConversionReadRepository.listBinding());

        listings.forEach((name, binding) ->
                assertThat(ListingIndexCoverage.missing(dsl, binding)).as("the %s listing", name).isEmpty());
    }

    @Test
    void aSortableFieldWithoutAnIndexIsReported() {
        FilterSchema schema = FilterSchema.of("id", "id",
                FieldSpec.of("id", FieldType.UUID).asSortable(),
                FieldSpec.of("kind", FieldType.STRING).asSortable());
        ListBinding binding = ListBinding.of(schema)
                .column("id", Tables.LINKS.ID)
                .column("kind", Tables.LINKS.KIND)
                .build();

        assertThat(ListingIndexCoverage.missing(dsl, binding)).singleElement().asString()
                .contains("kind").contains("links");
    }

    @Test
    void aContainsFieldWithoutATrigramIndexIsReported() {
        FilterSchema schema = FilterSchema.of("id", "id",
                FieldSpec.of("id", FieldType.UUID).asSortable(),
                FieldSpec.of("external_ref", FieldType.STRING).with(Op.CONTAINS).asNullable());
        ListBinding binding = ListBinding.of(schema)
                .column("id", Tables.LINKS.ID)
                .column("external_ref", Tables.LINKS.EXTERNAL_REF)
                .build();

        assertThat(ListingIndexCoverage.missing(dsl, binding)).singleElement().asString()
                .contains("external_ref").contains("gin_trgm_ops");
    }
}
