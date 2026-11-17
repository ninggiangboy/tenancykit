package dev.ngb.customer.webhook.application.endpoint.command;

import dev.ngb.core.error.NotFoundException;
import dev.ngb.core.runtime.Clock;
import dev.ngb.core.runtime.UnitOfWork;
import dev.ngb.core.security.SecretCipher;
import dev.ngb.core.version.ExpectedVersion;
import dev.ngb.customer.webhook.application.endpoint.port.EventTypeCatalog;
import dev.ngb.customer.webhook.application.endpoint.port.SigningKeys;
import dev.ngb.customer.webhook.application.endpoint.port.WebhookEndpointRepository;
import dev.ngb.customer.webhook.application.endpoint.service.EndpointRules;
import dev.ngb.customer.webhook.application.endpoint.service.WebhookSettings;
import dev.ngb.customer.webhook.domain.endpoint.EndpointStatus;
import dev.ngb.customer.webhook.domain.endpoint.WebhookEndpoint;
import dev.ngb.customer.webhook.domain.endpoint.WebhookEndpointId;
import java.time.Instant;
import java.util.List;

/** Reads and changes the endpoints of the current tenant. */
public class ManageEndpointUseCase {

    /** A change request: a null field is left as it is. */
    public record Change(String url, String description, List<String> eventTypes, EndpointStatus status) {}

    /** The endpoint after a rotation, with the new secret the caller sees once. */
    public record Rotated(WebhookEndpoint endpoint, String signingSecret) {}

    private final UnitOfWork unitOfWork;
    private final WebhookEndpointRepository endpoints;
    private final SecretCipher cipher;
    private final SigningKeys keys;
    private final EventTypeCatalog catalog;
    private final WebhookSettings settings;
    private final Clock clock;

    public ManageEndpointUseCase(
            UnitOfWork unitOfWork,
            WebhookEndpointRepository endpoints,
            SecretCipher cipher,
            SigningKeys keys,
            EventTypeCatalog catalog,
            WebhookSettings settings,
            Clock clock) {
        this.unitOfWork = unitOfWork;
        this.endpoints = endpoints;
        this.cipher = cipher;
        this.keys = keys;
        this.catalog = catalog;
        this.settings = settings;
        this.clock = clock;
    }

    public WebhookEndpoint update(WebhookEndpointId id, Change change, ExpectedVersion expected) {
        String url = change.url() == null ? null : EndpointRules.url(change.url(), settings);
        var types = change.eventTypes() == null ? null : EndpointRules.eventTypes(change.eventTypes(), catalog);
        return unitOfWork.transactional(() -> {
            WebhookEndpoint endpoint = endpoints.lockById(id)
                    .orElseThrow(() -> new NotFoundException("No webhook endpoint exists here."));
            expected.check(endpoint.version());
            Instant now = clock.now();
            endpoint.reconfigure(
                    url == null ? endpoint.url() : url,
                    change.description() == null ? endpoint.description() : change.description(),
                    types == null ? endpoint.eventTypes() : types,
                    now);
            if (change.status() != null) {
                endpoint.changeStatus(change.status(), now);
            }
            endpoints.update(endpoint);
            return endpoint;
        });
    }

    /**
     * Deletes the endpoint: it stops receiving and leaves every list. The deliveries it already received stay
     * readable, because they are the Tenant's evidence of what was sent.
     */
    public void delete(WebhookEndpointId id, ExpectedVersion expected) {
        unitOfWork.transactional(() -> {
            WebhookEndpoint endpoint = endpoints.lockById(id)
                    .filter(found -> !found.isDeleted())
                    .orElseThrow(() -> new NotFoundException("No webhook endpoint exists here."));
            expected.check(endpoint.version());
            endpoint.delete(clock.now());
            endpoints.delete(endpoint);
            return endpoint;
        });
    }

    /** Replaces the signing secret at once. A delivery still waiting is signed with the new one. */
    public Rotated rotateSecret(WebhookEndpointId id) {
        return unitOfWork.transactional(() -> {
            WebhookEndpoint endpoint = endpoints.lockById(id)
                    .orElseThrow(() -> new NotFoundException("No webhook endpoint exists here."));
            Instant now = clock.now();
            SigningKeys.Key key = keys.generate();
            endpoint.rotateSecret(key.keyId(), cipher.encrypt(key.secret(), id.toString()), now);
            endpoints.update(endpoint);
            return new Rotated(endpoint, key.secret());
        });
    }

}
