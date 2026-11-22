package dev.ngb.loyalty.webhook.application.endpoint.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.ngb.core.error.NotFoundException;
import dev.ngb.core.id.TenantId;
import dev.ngb.core.security.SecretCipher;
import dev.ngb.core.version.ExpectedVersion;
import dev.ngb.loyalty.support.ListQueries;
import dev.ngb.loyalty.support.LoyaltyIntegrationTest;
import dev.ngb.loyalty.webhook.application.endpoint.port.WebhookEndpointRepository;
import dev.ngb.loyalty.webhook.application.endpoint.query.EndpointListing;
import dev.ngb.loyalty.webhook.application.endpoint.query.EndpointQueries;
import dev.ngb.loyalty.webhook.application.endpoint.query.WebhookEndpointView;
import dev.ngb.loyalty.webhook.domain.endpoint.EndpointStatus;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * The endpoint lifecycle: a secret appears once, on creation and on rotation, and never in a read; a delete stops
 * delivery and keeps the history.
 *
 * <p>The secret is stored only as ciphertext, so a read that returned it would mean the cipher was bypassed. These cases
 * assert the two places the plaintext is allowed and that a read is not one of them.
 */
class EndpointLifecycleTest extends LoyaltyIntegrationTest {

    @Autowired CreateEndpointUseCase create;
    @Autowired ManageEndpointUseCase manage;
    @Autowired EndpointQueries queries;
    @Autowired SecretCipher cipher;
    @Autowired WebhookEndpointRepository endpoints;

    @Test
    void creatingAnEndpointReturnsItsSecretExactlyOnce() {
        TenantId tenant = new TenantId(UUID.randomUUID());

        CreateEndpointUseCase.Result result = inTenant(tenant, () -> create.execute(
                new CreateEndpointUseCase.Command(
                        "https://hooks.example.com/loyalty", "the tenant's own system", List.of("points.earned"))));

        assertThat(result.signingSecret()).isNotBlank();
        assertThat(result.endpoint().eventTypes()).containsExactly("points.earned");
        WebhookEndpointView view = inTenant(tenant, () -> queries.get(result.endpoint().id().value()));
        assertThat(view.url()).isEqualTo("https://hooks.example.com/loyalty");
        assertThat(view.keyId()).isEqualTo(result.endpoint().keyId());
    }

    @Test
    void theStoredSecretIsCiphertextThatDecryptsWithTheEndpointAsContext() {
        TenantId tenant = new TenantId(UUID.randomUUID());
        CreateEndpointUseCase.Result result = inTenant(tenant, () -> create.execute(
                new CreateEndpointUseCase.Command("https://hooks.example.com/x", null, List.of("*"))));

        String stored = result.endpoint().secretCiphertext();

        assertThat(stored).doesNotContain(result.signingSecret());
        assertThat(cipher.decrypt(stored, result.endpoint().id().toString()))
                .as("the endpoint id is the associated data, so a ciphertext moved to another row does not decrypt")
                .isEqualTo(result.signingSecret());
    }

    @Test
    void rotatingTheSecretReplacesItAndAnswersTheNewOne() {
        TenantId tenant = new TenantId(UUID.randomUUID());
        CreateEndpointUseCase.Result created = inTenant(tenant, () -> create.execute(
                new CreateEndpointUseCase.Command("https://hooks.example.com/x", null, List.of("member.enrolled"))));

        ManageEndpointUseCase.Rotated rotated = inTenant(tenant, () -> manage.rotateSecret(created.endpoint().id()));

        assertThat(rotated.signingSecret()).isNotEqualTo(created.signingSecret());
        assertThat(cipher.decrypt(rotated.endpoint().secretCiphertext(), rotated.endpoint().id().toString()))
                .isEqualTo(rotated.signingSecret());
    }

    @Test
    void anotherTenantCannotReadOrDeleteTheEndpoint() {
        TenantId owner = new TenantId(UUID.randomUUID());
        TenantId other = new TenantId(UUID.randomUUID());
        CreateEndpointUseCase.Result created = inTenant(owner, () -> create.execute(
                new CreateEndpointUseCase.Command("https://hooks.example.com/x", null, List.of("*"))));

        assertThatThrownBy(() -> inTenant(other, () -> queries.get(created.endpoint().id().value())))
                .isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> inTenant(other, () -> manage.delete(
                        created.endpoint().id(), ExpectedVersion.fromIfMatch("\"1\""))))
                .isInstanceOf(NotFoundException.class);
        assertThat(inTenant(other, () -> queries.list(ListQueries.of(EndpointListing.SCHEMA))).data()).isEmpty();
    }

    @Test
    void aDisabledEndpointStopsSubscribing() {
        TenantId tenant = new TenantId(UUID.randomUUID());
        CreateEndpointUseCase.Result created = inTenant(tenant, () -> create.execute(
                new CreateEndpointUseCase.Command("https://hooks.example.com/x", null, List.of("*"))));

        inTenant(tenant, () -> manage.update(
                created.endpoint().id(),
                new ManageEndpointUseCase.Change(null, null, null, EndpointStatus.DISABLED),
                ExpectedVersion.fromIfMatch("\"1\"")));

        assertThat(inTenant(tenant, () -> endpoints.findSubscribed("points.earned")))
                .as("a disabled endpoint receives nothing")
                .isEmpty();
    }

    @Test
    void deletingAnEndpointRemovesItAndStopsSubscription() {
        TenantId tenant = new TenantId(UUID.randomUUID());
        CreateEndpointUseCase.Result created = inTenant(tenant, () -> create.execute(
                new CreateEndpointUseCase.Command("https://hooks.example.com/x", null, List.of("*"))));

        inTenant(tenant, () -> manage.delete(created.endpoint().id(), ExpectedVersion.fromIfMatch("\"1\"")));

        assertThatThrownBy(() -> inTenant(tenant, () -> queries.get(created.endpoint().id().value())))
                .isInstanceOf(NotFoundException.class);
        assertThat(inTenant(tenant, () -> endpoints.findSubscribed("member.enrolled"))).isEmpty();
    }
}
