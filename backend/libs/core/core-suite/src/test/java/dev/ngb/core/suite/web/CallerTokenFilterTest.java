package dev.ngb.core.suite.web;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** The prefix-to-token-kind rule, tested on the decision alone so no server and no key set is needed. */
class CallerTokenFilterTest {

    private static final String TENANT_SYSTEM = "tenant-system/11111111-1111-7111-8111-111111111111";
    private static final String SERVICE = "service/platform";

    @Test
    void systemAcceptsATenantSystemTokenAndOnlyThat() {
        assertThat(CallerTokenFilter.accepts("system", true, TENANT_SYSTEM)).isTrue();
        assertThat(CallerTokenFilter.accepts("system", false, "kc-user")).isFalse();
        assertThat(CallerTokenFilter.accepts("system", true, SERVICE)).isFalse();
    }

    @Test
    void internalAcceptsAServiceTokenAndOnlyThat() {
        assertThat(CallerTokenFilter.accepts("internal", true, SERVICE)).isTrue();
        assertThat(CallerTokenFilter.accepts("internal", true, TENANT_SYSTEM)).isFalse();
        assertThat(CallerTokenFilter.accepts("internal", false, "kc-user")).isFalse();
    }

    @Test
    void thePersonPrefixesAcceptAKeycloakTokenAndRefuseAsuiteToken() {
        for (String prefix : new String[] {"api", "admin"}) {
            assertThat(CallerTokenFilter.accepts(prefix, false, "kc-user")).isTrue();
            assertThat(CallerTokenFilter.accepts(prefix, true, TENANT_SYSTEM)).isFalse();
        }
    }

    @Test
    void aPathOutsideEveryPrefixAcceptsAnything() {
        assertThat(CallerTokenFilter.accepts(null, true, TENANT_SYSTEM)).isTrue();
        assertThat(CallerTokenFilter.accepts(null, false, "kc-user")).isTrue();
    }
}
