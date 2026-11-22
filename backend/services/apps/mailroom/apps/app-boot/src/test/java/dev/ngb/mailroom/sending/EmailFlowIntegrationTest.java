package dev.ngb.mailroom.sending;

import static org.assertj.core.api.Assertions.assertThat;

import dev.ngb.core.id.TenantId;
import dev.ngb.core.tenancy.RequestScope;
import dev.ngb.core.tenancy.TenantContext;
import dev.ngb.core.version.ExpectedVersion;
import dev.ngb.mailroom.Application;
import dev.ngb.mailroom.audience.api.eligibility.SendEligibility;
import dev.ngb.mailroom.audience.application.group.command.CreateGroupUseCase;
import dev.ngb.mailroom.audience.application.group.command.ManageMembersUseCase;
import dev.ngb.mailroom.audience.application.group.port.GroupRepository;
import dev.ngb.mailroom.audience.application.receiver.command.UpsertReceiverUseCase;
import dev.ngb.mailroom.audience.domain.group.ReceiverGroup;
import dev.ngb.mailroom.audience.domain.receiver.Receiver;
import dev.ngb.mailroom.content.application.template.command.CreateTemplateUseCase;
import dev.ngb.mailroom.sending.application.campaign.command.CreateCampaignUseCase;
import dev.ngb.mailroom.sending.application.campaign.command.PublishCampaignBatchUseCase;
import dev.ngb.mailroom.sending.application.campaign.command.StartCampaignUseCase;
import dev.ngb.mailroom.sending.application.campaign.query.CampaignStatsQueries;
import dev.ngb.mailroom.sending.application.campaign.query.CampaignStatsView;
import dev.ngb.mailroom.sending.application.email.command.ApplyDeliveryResultUseCase;
import dev.ngb.mailroom.sending.application.email.query.EmailListing;
import dev.ngb.mailroom.sending.application.email.query.EmailQueries;
import dev.ngb.mailroom.sending.application.email.query.EmailView;
import dev.ngb.mailroom.sending.application.tracking.command.RecordClickUseCase;
import dev.ngb.mailroom.sending.application.tracking.command.RecordOpenUseCase;
import dev.ngb.mailroom.sending.application.tracking.command.UnsubscribeUseCase;
import dev.ngb.mailroom.sending.application.tracking.port.TrackingTokens;
import dev.ngb.mailroom.sending.domain.campaign.Campaign;
import dev.ngb.mailroom.sending.domain.campaign.CampaignTargetType;
import dev.ngb.mailroom.sending.domain.email.DeliveryOutcome;
import dev.ngb.mailroom.sending.domain.tracking.TrackingAction;
import dev.ngb.mailroom.shared.EmailKind;
import dev.ngb.mailroom.support.ListQueries;
import dev.ngb.mailroom.support.PostgresContainerConfig;
import dev.ngb.mailroom.support.RabbitContainerConfig;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * The headline scenario end to end, against the real database and broker wiring: template → receiver → group →
 * campaign → a published email with rewritten links → mailer's success → an open and a click → a one-click unsubscribe
 * → statistics that match what the test did.
 *
 * <p>It drives the use cases directly, in a bound tenant scope, because the HTTP edge's authentication is proved
 * elsewhere; what this test adds is the business outcome and the isolation each use case inherits.
 */
@SpringBootTest(classes = Application.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("api")
class EmailFlowIntegrationTest {

    @Autowired
    TenantContext tenantContext;
    @Autowired
    CreateTemplateUseCase createTemplate;
    @Autowired
    UpsertReceiverUseCase upsertReceiver;
    @Autowired
    CreateGroupUseCase createGroup;
    @Autowired
    ManageMembersUseCase members;
    @Autowired
    GroupRepository groups;
    @Autowired
    CreateCampaignUseCase createCampaign;
    @Autowired
    StartCampaignUseCase startCampaign;
    @Autowired
    PublishCampaignBatchUseCase publish;
    @Autowired
    ApplyDeliveryResultUseCase applyResult;
    @Autowired
    EmailQueries emails;
    @Autowired
    CampaignStatsQueries stats;
    @Autowired
    TrackingTokens tokens;
    @Autowired
    RecordOpenUseCase recordOpen;
    @Autowired
    RecordClickUseCase recordClick;
    @Autowired
    UnsubscribeUseCase unsubscribe;
    @Autowired
    SendEligibility eligibility;

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
        registry.add("mailroom.housekeeping.enabled", () -> "false");
        // Tracking has no shipped default: a blank key fails startup, so the test supplies its own.
        registry.add("mailroom.tracking.keys.k1", () -> "test-tracking-secret");
    }

    @Test
    void aCampaignIsSentTrackedUnsubscribedAndCounted() {
        TenantId tenant = new TenantId(UUID.randomUUID());
        tenantContext.runAs(RequestScope.tenant(tenant), () -> {
            createTemplate.execute(new CreateTemplateUseCase.Command(
                    "welcome",
                    "Welcome",
                    "Hi {{receiver.first_name}}",
                    "<html><body><p>Hi {{receiver.first_name}}</p>"
                            + "<a href=\"https://example.com/landing\">Read more</a></body></html>",
                    "Hi {{receiver.first_name}}"));

            Receiver receiver = upsertReceiver.execute(new UpsertReceiverUseCase.Command(
                    "Customer@Example.com", "An", null, Map.of("plan", "pro")));
            assertThat(receiver.emailNormalized()).isEqualTo("customer@example.com");

            ReceiverGroup group = createGroup.execute("Customers");
            assertThat(members.add(group.id(), List.of(receiver.id()))).isEqualTo(1);

            Campaign campaign = createCampaign.execute(new CreateCampaignUseCase.Command(
                    "Launch", "welcome", CampaignTargetType.GROUP, group.id(), null));
            startCampaign.execute(campaign.id(), ExpectedVersion.any());

            PublishCampaignBatchUseCase.Published published = publish.execute();
            assertThat(published.published()).isEqualTo(1);

            EmailView queued = emails.list(ListQueries.of(EmailListing.SCHEMA,
                    "filter[campaign_id][eq]", campaign.id().toString(), "limit", "50")).data().getFirst();
            assertThat(queued.status()).isEqualTo("QUEUED");
            assertThat(queued.bodyHtml()).contains("/t/c/").contains("/t/o/");
            assertThat(queued.bodyHtml()).doesNotContain("href=\"https://example.com/landing\"");

            applyResult.execute(new DeliveryOutcome(
                    queued.id().toString(), "SUCCEEDED", 1, Instant.now(), "ses-1", null));
            assertThat(emails.get(queued.id()).status()).isEqualTo("SENT");
            // A replay with the same completedAt is a no-op, so the ledger cannot flicker.
            assertThat(applyResult.execute(new DeliveryOutcome(
                            queued.id().toString(), "SUCCEEDED", 1, Instant.now().minusSeconds(60), null, null)))
                    .isFalse();

            recordOpen.record(tokens.sign(tenant, queued.id(), 0, TrackingAction.OPEN));
            Optional<String> target =
                    recordClick.click(tokens.sign(tenant, queued.id(), 0, TrackingAction.CLICK));
            assertThat(target).contains("https://example.com/landing");

            unsubscribe.unsubscribe(tokens.sign(tenant, queued.id(), 0, TrackingAction.UNSUBSCRIBE));
            assertThat(eligibility.allows(tenant, "customer@example.com", EmailKind.MARKETING)).isFalse();
            assertThat(eligibility.allows(tenant, "customer@example.com", EmailKind.TRANSACTIONAL)).isTrue();
            // The unsubscribe is idempotent: a second click changes nothing.
            unsubscribe.unsubscribe(tokens.sign(tenant, queued.id(), 0, TrackingAction.UNSUBSCRIBE));

            CampaignStatsView view = stats.stats(campaign.id());
            assertThat(view.recipients()).isEqualTo(1);
            assertThat(view.published()).isEqualTo(1);
            assertThat(view.sent()).isEqualTo(1);
            assertThat(view.failed()).isZero();
            assertThat(view.opened()).isEqualTo(1);
            assertThat(view.openEvents()).isEqualTo(1);
            assertThat(view.clicked()).isEqualTo(1);
            assertThat(view.unsubscribed()).isEqualTo(1);
        });
    }
}
