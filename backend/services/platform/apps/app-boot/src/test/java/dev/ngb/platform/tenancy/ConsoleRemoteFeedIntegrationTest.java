package dev.ngb.platform.tenancy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import dev.ngb.core.security.Actor;
import dev.ngb.core.security.ActorType;
import dev.ngb.core.security.PermissionScope;
import dev.ngb.core.tenancy.RequestScope;
import dev.ngb.core.tenancy.TenantContext;
import dev.ngb.core.version.ExpectedVersion;
import dev.ngb.platform.Application;
import dev.ngb.platform.boot.security.SuiteTokenIssuer;
import dev.ngb.platform.support.PlatformContainerConfig;
import dev.ngb.platform.tenancy.application.entitlement.command.SetAppEntitlementUseCase;
import dev.ngb.platform.tenancy.application.tenant.command.CreateTenantUseCase;
import dev.ngb.platform.tenancy.application.uiremote.command.RegisterUiRemoteVersionUseCase;
import dev.ngb.platform.tenancy.application.uiremote.command.SetUiRemoteStateUseCase;
import dev.ngb.platform.tenancy.application.uiremote.query.ConsoleRemoteQueries;
import dev.ngb.platform.tenancy.application.uiremote.query.ConsoleRemoteView;
import dev.ngb.platform.tenancy.domain.tenant.Tenant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * The feed follows the caller: a Platform user gets the remotes built for the Platform Shell, a Tenant user the ones
 * built for the Tenant Shell and only where the tenant has enabled the app — except the platform console, which every
 * tenant's admins use. A disabled remote and one with no active version never appear.
 */
@SpringBootTest(classes = Application.class)
@AutoConfigureMockMvc
@Import(PlatformContainerConfig.class)
class ConsoleRemoteFeedIntegrationTest {

    private static final String INTEGRITY = "sha384-" + "A".repeat(64);

    @Autowired ConsoleRemoteQueries feed;
    @Autowired RegisterUiRemoteVersionUseCase register;
    @Autowired SetUiRemoteStateUseCase setState;
    @Autowired SetAppEntitlementUseCase setApp;
    @Autowired CreateTenantUseCase createTenant;
    @Autowired TenantContext tenantContext;
    @Autowired SuiteTokenIssuer issuer;
    @Autowired MockMvc mvc;

    @Test
    void aPlatformUserGetsOnlyTheRemotesBuiltForThePlatformShell() {
        String platformRemote = publish("platform", List.of("PLATFORM", "TENANT"), true);
        String tenantOnly = publish("billing", List.of("TENANT"), true);

        List<String> names = onPlatform(() -> names(feed.feed(platformActor())));

        assertThat(names).contains(platformRemote);
        assertThat(names).doesNotContain(tenantOnly);
    }

    @Test
    void aTenantUserSeesTheConsoleWithoutAnEntitlementButNotAnAppTheTenantLacks() {
        Tenant tenant = newTenant();
        String platformRemote = publish("platform", List.of("PLATFORM", "TENANT"), true);
        String billingRemote = publish("billing", List.of("TENANT"), true);

        List<String> before = onTenant(tenant, () -> names(feed.feed(tenantActor(tenant))));
        assertThat(before).contains(platformRemote);
        assertThat(before).doesNotContain(billingRemote);

        onPlatform(() -> setApp.execute(new SetAppEntitlementUseCase.Command(
                tenant.id(), "billing", true, "user/tester", "req-1", ExpectedVersion.any())));

        List<String> after = onTenant(tenant, () -> names(feed.feed(tenantActor(tenant))));
        assertThat(after).contains(platformRemote, billingRemote);
    }

    @Test
    void aDisabledRemoteAndOneWithNoActiveVersionAreNotInTheFeed() {
        String disabled = publish("billing", List.of("PLATFORM", "TENANT"), true);
        String neverActivated = publish("billing", List.of("PLATFORM", "TENANT"), false);
        String active = publish("billing", List.of("PLATFORM", "TENANT"), true);
        onPlatform(() -> setState.execute(new SetUiRemoteStateUseCase.Command(
                disabled, false, null, null, null, "user/tester", "req-2", ExpectedVersion.any())));

        List<String> names = onPlatform(() -> names(feed.feed(platformActor())));

        assertThat(names).contains(active);
        assertThat(names).doesNotContain(disabled, neverActivated);
    }

    @Test
    void theFeedEndpointAnswersAnyAuthenticatedCaller() throws Exception {
        String platformRemote = publish("platform", List.of("PLATFORM", "TENANT"), true);
        String token = issuer.issueService("reader", Set.of(), "platform").token();

        MvcResult result = mvc.perform(get("/api/v1/console/remotes")
                        .header("Host", "localhost")
                        .header("Authorization", "Bearer " + token))
                .andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        assertThat(result.getResponse().getContentAsString()).contains("\"" + platformRemote + "\"");
    }

    @Test
    void anUnauthenticatedCallerIs401() throws Exception {
        MvcResult result = mvc.perform(get("/api/v1/console/remotes").header("Host", "localhost")).andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(401);
    }

    private String publish(String appCode, List<String> audiences, boolean activate) {
        String name = "ui" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        String version = "1.0.0+abcdef0";
        return onPlatform(() -> {
            register.execute(new RegisterUiRemoteVersionUseCase.Command(
                    name, version, "/console/remotes/" + name + "/" + version + "/mf-manifest.json", INTEGRITY, 1,
                    appCode, name, audiences, 10, "service/frontend-ci", "req-1"));
            if (activate) {
                setState.execute(new SetUiRemoteStateUseCase.Command(
                        name, true, version, null, null, "user/operator", "req-2", ExpectedVersion.any()));
            }
            return name;
        });
    }

    private Tenant newTenant() {
        String code = "feed" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        return onPlatform(() -> createTenant.execute(new CreateTenantUseCase.Command(code, "Feed " + code, "user/tester", "req-1")));
    }

    private static Actor platformActor() {
        return new Actor(
                ActorType.SERVICE, UUID.randomUUID(), RequestScope.PLATFORM_TENANT_ID, PermissionScope.PLATFORM,
                "service/test", Set.of());
    }

    private static Actor tenantActor(Tenant tenant) {
        return new Actor(ActorType.USER, UUID.randomUUID(), tenant.id(), PermissionScope.TENANT, "user/test", Set.of());
    }

    private static List<String> names(List<ConsoleRemoteView> remotes) {
        return remotes.stream().map(ConsoleRemoteView::name).toList();
    }

    private <T> T onPlatform(Supplier<T> work) {
        return tenantContext.callAs(RequestScope.platform(), work);
    }

    private <T> T onTenant(Tenant tenant, Supplier<T> work) {
        return tenantContext.callAs(RequestScope.tenant(tenant.id()), work);
    }
}
