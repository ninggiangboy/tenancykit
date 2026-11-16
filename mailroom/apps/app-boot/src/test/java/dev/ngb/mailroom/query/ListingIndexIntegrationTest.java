package dev.ngb.mailroom.query;

import static org.assertj.core.api.Assertions.assertThat;

import dev.ngb.core.spring.query.ListBinding;
import dev.ngb.core.spring.query.ListingIndexCoverage;
import dev.ngb.mailroom.Application;
import dev.ngb.mailroom.audience.adapter.audience.persistence.JooqAudienceReadRepository;
import dev.ngb.mailroom.audience.adapter.segment.persistence.JooqSegmentReadRepository;
import dev.ngb.mailroom.content.adapter.template.persistence.JooqTemplateReadRepository;
import dev.ngb.mailroom.sending.adapter.campaign.persistence.JooqCampaignReadRepository;
import dev.ngb.mailroom.sending.adapter.email.persistence.JooqEmailReadRepository;
import dev.ngb.mailroom.support.PostgresContainerConfig;
import dev.ngb.mailroom.support.RabbitContainerConfig;
import java.util.ArrayList;
import java.util.List;
import org.jooq.DSLContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Every listing sorts only on fields that lead an index of their table, so a sort a caller asks for never scans the
 * whole table. Runs against the migrated schema.
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
        registry.add("mailroom.jobs.enabled", () -> "false");
        registry.add("mailroom.tracking.keys.k1", () -> "test-tracking-secret");
    }

    @Test
    void everyListingIsCovered() {
        List<ListBinding> bindings = new ArrayList<>();
        bindings.addAll(JooqTemplateReadRepository.listBindings());
        bindings.addAll(JooqAudienceReadRepository.listBindings());
        bindings.add(JooqCampaignReadRepository.listBinding());
        bindings.add(JooqEmailReadRepository.listBinding());
        bindings.add(JooqSegmentReadRepository.listBinding());

        for (ListBinding binding : bindings) {
            assertThat(ListingIndexCoverage.missing(dsl, binding)).as("the listing of %s", binding.schema()).isEmpty();
        }
    }
}
