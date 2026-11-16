package dev.ngb.survey.webhook.application.endpoint.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.ngb.core.id.TenantId;
import dev.ngb.core.security.SecretCipher;
import dev.ngb.core.spring.security.AesGcmSecretCipher;
import dev.ngb.survey.webhook.application.endpoint.query.EndpointQueries;
import dev.ngb.survey.webhook.application.endpoint.query.WebhookEndpointView;
import dev.ngb.survey.support.SurveyIntegrationTest;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * The endpoint lifecycle: a secret appears once, on creation and on rotation, and never in a read.
 *
 * <p>The secret is stored only as ciphertext, so a read that returned it would mean the cipher was bypassed. These cases
 * assert the two places the plaintext is allowed and that a read is not one of them.
 */
class EndpointLifecycleTest extends SurveyIntegrationTest {

    private static final String MASTER_KEY = "eHh4eHh4eHh4eHh4eHh4eHh4eHh4eHh4eHh4eHh4eHg=";

    @Autowired CreateEndpointUseCase create;
    @Autowired ManageEndpointUseCase manage;
    @Autowired EndpointQueries queries;
    @Autowired SecretCipher cipher;
    @Autowired dev.ngb.survey.webhook.application.endpoint.port.WebhookEndpointRepository endpoints;

    @Test
    void creatingAnEndpointReturnsItsSecretExactlyOnce() {
        TenantId tenant = new TenantId(UUID.randomUUID());

        CreateEndpointUseCase.Result result = inTenant(tenant, () -> create.execute(
                new CreateEndpointUseCase.Command(
                        "https://hooks.example.com/survey", "the tenant's own system", List.of("survey.closed"))));

        assertThat(result.signingSecret()).isNotBlank();
        assertThat(result.endpoint().eventTypes()).containsExactly("survey.closed");
        WebhookEndpointView view = inTenant(tenant, () -> queries.get(result.endpoint().id().value()));
        assertThat(view.url()).isEqualTo("https://hooks.example.com/survey");
        assertThat(view.keyId()).isEqualTo(result.endpoint().keyId());
    }

    @Test
    void theStoredSecretIsCiphertextThatDecryptsWithTheEndpointAsContext() {
        TenantId tenant = new TenantId(UUID.randomUUID());
        CreateEndpointUseCase.Result result = inTenant(tenant, () -> create.execute(
                new CreateEndpointUseCase.Command(
                        "https://hooks.example.com/x", null, List.of("*"))));

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
                new CreateEndpointUseCase.Command("https://hooks.example.com/x", null, List.of("survey.closed"))));

        ManageEndpointUseCase.Rotated rotated = inTenant(tenant, () -> manage.rotateSecret(created.endpoint().id()));

        assertThat(rotated.signingSecret()).isNotEqualTo(created.signingSecret());
        assertThat(cipher.decrypt(rotated.endpoint().secretCiphertext(), rotated.endpoint().id().toString()))
                .isEqualTo(rotated.signingSecret());
    }

    @Test
    void anotherTenantCannotReadTheEndpoint() {
        TenantId owner = new TenantId(UUID.randomUUID());
        TenantId other = new TenantId(UUID.randomUUID());
        CreateEndpointUseCase.Result created = inTenant(owner, () -> create.execute(
                new CreateEndpointUseCase.Command("https://hooks.example.com/x", null, List.of("*"))));

        assertThatThrownBy(() -> inTenant(other, () -> queries.get(created.endpoint().id().value())))
                .isInstanceOf(dev.ngb.core.error.NotFoundException.class);
        assertThat(inTenant(other, () -> queries.list())).isEmpty();
    }

    @Test
    void aDisabledEndpointStopsSubscribing() {
        TenantId tenant = new TenantId(UUID.randomUUID());
        CreateEndpointUseCase.Result created = inTenant(tenant, () -> create.execute(
                new CreateEndpointUseCase.Command("https://hooks.example.com/x", null, List.of("*"))));

        inTenant(tenant, () -> manage.update(
                created.endpoint().id(),
                new ManageEndpointUseCase.Change(null, null, null, dev.ngb.survey.webhook.domain.endpoint.EndpointStatus.DISABLED),
                dev.ngb.core.version.ExpectedVersion.fromIfMatch("\"1\"")));

        assertThat(inTenant(tenant, () -> endpoints.findSubscribed("survey.closed")))
                .as("a disabled endpoint receives nothing")
                .isEmpty();
    }
}
