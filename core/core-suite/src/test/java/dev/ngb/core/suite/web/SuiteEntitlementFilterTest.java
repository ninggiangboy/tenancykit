package dev.ngb.core.suite.web;

import static org.assertj.core.api.Assertions.assertThat;

import dev.ngb.core.spring.tenancy.ScopedTenantContext;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * The entitlement filter: a tenant that has not enabled the app is refused with the same problem body the tenant
 * filter writes, and a health probe bypasses the check.
 */
class SuiteEntitlementFilterTest {

    private final ScopedTenantContext tenantContext = new ScopedTenantContext();
    private final MockHttpServletResponse response = new MockHttpServletResponse();

    @Test
    void anEnabledTenantReachesTheChain() throws Exception {
        SuiteEntitlementFilter filter = filter(true);
        AtomicBoolean reached = new AtomicBoolean(false);

        filter.doFilter(request("/api/v1/orders"), response, (req, res) -> reached.set(true));

        assertThat(reached).isTrue();
        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    void aDisabledTenantIsRefusedWithoutReachingTheChain() throws Exception {
        SuiteEntitlementFilter filter = filter(false);
        AtomicBoolean reached = new AtomicBoolean(false);

        filter.doFilter(request("/api/v1/orders"), response, (req, res) -> reached.set(true));

        assertThat(reached).isFalse();
        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getContentType()).isEqualTo(SuiteProblems.PROBLEM_JSON);
        assertThat(response.getContentAsString()).contains(SuiteProblems.APP_NOT_ENABLED).contains("billing");
    }

    @Test
    void aHealthProbeBypassesTheCheck() throws Exception {
        SuiteEntitlementFilter filter = filter(false);
        AtomicBoolean reached = new AtomicBoolean(false);

        filter.doFilter(request("/actuator/health"), response, (req, res) -> reached.set(true));

        assertThat(reached).isTrue();
        assertThat(response.getStatus()).isEqualTo(200);
    }

    private SuiteEntitlementFilter filter(boolean enabled) {
        return new SuiteEntitlementFilter(new SuiteEntitlements(null, null, tenantContext) {
            @Override
            public boolean enabledFor(String appCode) {
                return enabled;
            }
        }, "billing");
    }

    private static MockHttpServletRequest request(String uri) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", uri);
        request.setServerName("acme.localhost");
        return request;
    }
}
