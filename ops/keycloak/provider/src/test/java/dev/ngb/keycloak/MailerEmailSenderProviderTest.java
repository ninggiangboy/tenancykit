package dev.ngb.keycloak;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.keycloak.email.EmailException;

class MailerEmailSenderProviderTest {

    private static final UUID TENANT = UUID.fromString("0198c1a0-0000-7000-8000-000000000042");

    private final List<MailerCommand> published = new ArrayList<>();
    private final MailerEmailSenderProvider sender = new MailerEmailSenderProvider(published::add, "noreply");

    @Test
    void aUserWithATenantGetsOneCommandForThatTenant() throws EmailException {
        sender.send(Map.of(), StubUser.of("an@acme.vn", Map.of("tenant_id", TENANT.toString())), "Subject", "text", "<p>x</p>");

        assertEquals(1, published.size());
        assertEquals(TENANT, published.get(0).tenantId());
    }

    @Test
    void aUserWithoutATenantIsRefusedSoNoMailGoesOutUnderAGuessedTenant() {
        EmailException failure = assertThrows(EmailException.class,
                () -> sender.send(Map.of(), StubUser.of("an@acme.vn", Map.of()), "Subject", "text", null));

        assertTrue(failure.getMessage().contains("tenant_id"));
        assertTrue(published.isEmpty());
    }

    @Test
    void aMalformedTenantIsRefused() {
        assertThrows(EmailException.class, () -> sender.send(
                Map.of(), StubUser.of("an@acme.vn", Map.of("tenant_id", "acme")), "Subject", "text", null));
        assertTrue(published.isEmpty());
    }

    @Test
    void aUserWithoutAnEmailIsRefused() {
        assertThrows(EmailException.class, () -> sender.send(
                Map.of(), StubUser.of(" ", Map.of("tenant_id", TENANT.toString())), "Subject", "text", null));
        assertTrue(published.isEmpty());
    }

    @Test
    void aBrokerFailureIsAnEmailExceptionSoKeycloakLogsItAndTheResetPageStaysNeutral() {
        MailerEmailSenderProvider failing = new MailerEmailSenderProvider(command -> {
            throw new IOException("broker down");
        }, "noreply");

        EmailException failure = assertThrows(EmailException.class, () -> failing.send(
                Map.of(), StubUser.of("an@acme.vn", Map.of("tenant_id", TENANT.toString())), "Subject", "text", null));

        assertTrue(failure.getCause() instanceof IOException);
    }

    @Test
    void aBareAddressIsRefusedBecauseItHasNoTenant() {
        assertThrows(EmailException.class,
                () -> sender.send(Map.of(), "an@acme.vn", "Subject", "text", null));
        assertTrue(published.isEmpty());
    }
}
