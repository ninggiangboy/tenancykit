package dev.ngb.keycloak;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import org.junit.jupiter.api.Test;

class TenantUsernameTest {

    @Test
    void aWellFormedCodeIsATenantAndAnythingElseIsNot() {
        assertEquals(Optional.of("acme"), TenantUsername.tenantOf("acme"));
        assertEquals(Optional.of("platform"), TenantUsername.tenantOf(" platform "));
        assertEquals(Optional.of("acme-corp-2"), TenantUsername.tenantOf("acme-corp-2"));
        assertTrue(TenantUsername.tenantOf(null).isEmpty());
        assertTrue(TenantUsername.tenantOf("").isEmpty());
        assertTrue(TenantUsername.tenantOf("Acme").isEmpty());
        assertTrue(TenantUsername.tenantOf("a").isEmpty());
        assertTrue(TenantUsername.tenantOf("acme:evil").isEmpty());
        assertTrue(TenantUsername.tenantOf("-acme").isEmpty());
    }

    @Test
    void theTypedEmailIsPrefixedWithTheTenant() {
        assertEquals("acme:an@acme.com", TenantUsername.compose("acme", "an@acme.com"));
        assertEquals("acme:an@acme.com", TenantUsername.compose("acme", "  an@acme.com "));
    }

    @Test
    void aTypedFullUsernameCannotReachAnotherTenant() {
        assertEquals("acme:other:an@other.com", TenantUsername.compose("acme", "other:an@other.com"));
    }

    @Test
    void theLabelIsTheRedirectHostElseTheTenantCode() {
        assertEquals("acme.example.com", TenantUsername.label("acme", "https://acme.example.com/console/callback"));
        assertEquals("acme.localhost", TenantUsername.label("acme", "http://acme.localhost:5173/console/callback"));
        assertEquals("acme", TenantUsername.label("acme", null));
        assertEquals("acme", TenantUsername.label("acme", "not a uri"));
    }
}
