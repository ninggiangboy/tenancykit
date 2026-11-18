package dev.ngb.core.suite.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.ngb.core.id.TenantId;
import dev.ngb.core.spring.tenancy.ScopedTenantContext;
import dev.ngb.core.tenancy.RequestScope;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * The tenant filter: the scope comes from the hostname alone, an unknown host answers one generic problem and never
 * reaches the chain, a health probe runs on the platform scope, and the scope is released with the request.
 */
class SuiteTenantFilterTest {

    private static final RequestScope TENANT = RequestScope.tenant(new TenantId(UUID.randomUUID()));

    private final ScopedTenantContext tenantContext = new ScopedTenantContext();
    private final MockHttpServletResponse response = new MockHttpServletResponse();

    @Test
    void aTenantHostBindsThatScopeForTheChain() throws Exception {
        SuiteTenantFilter filter = filter(host -> Optional.of(TENANT));
        MockHttpServletRequest request = request("/api/v1/orders", "acme.localhost");
        AtomicReference<RequestScope> seen = new AtomicReference<>();

        filter.doFilter(request, response, (req, res) -> seen.set(tenantContext.current()));

        assertThat(seen.get()).isEqualTo(TENANT);
        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    void theClientCannotChooseTheTenantWithAHeader() throws Exception {
        SuiteTenantFilter filter = filter(host -> Optional.of(TENANT));
        MockHttpServletRequest request = request("/api/v1/orders", "acme.localhost");
        request.addHeader("tenant_id", UUID.randomUUID().toString());
        AtomicReference<RequestScope> seen = new AtomicReference<>();

        filter.doFilter(request, response, (req, res) -> seen.set(tenantContext.current()));

        assertThat(seen.get()).as("only the hostname decides the tenant").isEqualTo(TENANT);
    }

    @Test
    void anUnknownHostAnswersOneGenericProblemAndSkipsTheChain() throws Exception {
        SuiteTenantFilter filter = filter(host -> Optional.empty());
        MockHttpServletRequest request = request("/api/v1/orders", "unknown.localhost");
        AtomicReference<RequestScope> seen = new AtomicReference<>();

        filter.doFilter(request, response, (req, res) -> seen.set(tenantContext.current()));

        assertThat(response.getStatus()).isEqualTo(404);
        assertThat(response.getContentType()).isEqualTo(SuiteProblems.PROBLEM_JSON);
        assertThat(response.getContentAsString()).contains(SuiteProblems.NOT_FOUND);
        assertThat(seen).hasValue(null);
    }

    @Test
    void aHealthProbeRunsOnThePlatformScopeWhateverTheHost() throws Exception {
        SuiteTenantFilter filter = filter(host -> Optional.empty());
        MockHttpServletRequest request = request("/actuator/health", "unknown.localhost");
        AtomicReference<RequestScope> seen = new AtomicReference<>();

        filter.doFilter(request, response, (req, res) -> seen.set(tenantContext.current()));

        assertThat(seen.get()).isInstanceOf(RequestScope.Platform.class);
        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    void theScopeIsReleasedWithTheRequest() throws Exception {
        SuiteTenantFilter filter = filter(host -> Optional.of(TENANT));

        filter.doFilter(request("/api/v1/orders", "acme.localhost"), response, (req, res) -> {});

        assertThatThrownBy(tenantContext::current).isInstanceOf(IllegalStateException.class);
    }

    private SuiteTenantFilter filter(Function<String, Optional<RequestScope>> resolution) {
        return new SuiteTenantFilter(new SuiteHostResolver(null, null) {
            @Override
            public Optional<RequestScope> resolve(String hostname) {
                return resolution.apply(hostname);
            }
        }, tenantContext);
    }

    private static MockHttpServletRequest request(String uri, String host) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", uri);
        request.setServerName(host);
        return request;
    }
}
