package dev.ngb.loyalty.webhook.application.endpoint.command;

import dev.ngb.core.id.TenantId;
import dev.ngb.core.runtime.Clock;
import dev.ngb.core.runtime.IdGenerator;
import dev.ngb.core.runtime.UnitOfWork;
import dev.ngb.core.security.SecretCipher;
import dev.ngb.core.tenancy.TenantContext;
import dev.ngb.loyalty.webhook.application.endpoint.port.EventTypeCatalog;
import dev.ngb.loyalty.webhook.application.endpoint.port.SigningKeys;
import dev.ngb.loyalty.webhook.application.endpoint.port.WebhookEndpointRepository;
import dev.ngb.loyalty.webhook.application.endpoint.service.EndpointRules;
import dev.ngb.loyalty.webhook.application.endpoint.service.WebhookSettings;
import dev.ngb.loyalty.webhook.domain.endpoint.WebhookEndpoint;
import dev.ngb.loyalty.webhook.domain.endpoint.WebhookEndpointId;
import java.time.Instant;
import java.util.List;

/** Registers a destination and generates its signing secret, which is shown to the caller exactly once. */
public class CreateEndpointUseCase {

    /** The endpoint a Tenant asked for: where to send, what to send, and a description it recognises. */
    public record Command(String url, String description, List<String> eventTypes) {}

    /** {@code signingSecret} is the only time the plaintext exists outside the delivery worker. */
    public record Result(WebhookEndpoint endpoint, String signingSecret) {}

    private final UnitOfWork unitOfWork;
    private final WebhookEndpointRepository endpoints;
    private final SecretCipher cipher;
    private final SigningKeys keys;
    private final EventTypeCatalog catalog;
    private final WebhookSettings settings;
    private final TenantContext tenantContext;
    private final IdGenerator ids;
    private final Clock clock;

    public CreateEndpointUseCase(
            UnitOfWork unitOfWork,
            WebhookEndpointRepository endpoints,
            SecretCipher cipher,
            SigningKeys keys,
            EventTypeCatalog catalog,
            WebhookSettings settings,
            TenantContext tenantContext,
            IdGenerator ids,
            Clock clock) {
        this.unitOfWork = unitOfWork;
        this.endpoints = endpoints;
        this.cipher = cipher;
        this.keys = keys;
        this.catalog = catalog;
        this.settings = settings;
        this.tenantContext = tenantContext;
        this.ids = ids;
        this.clock = clock;
    }

    public Result execute(Command command) {
        String url = EndpointRules.url(command.url(), settings);
        var types = EndpointRules.eventTypes(command.eventTypes(), catalog);
        return unitOfWork.transactional(() -> {
            TenantId tenantId = tenantContext.requireTenant();
            Instant now = clock.now();
            WebhookEndpointId id = new WebhookEndpointId(ids.newId());
            SigningKeys.Key key = keys.generate();
            WebhookEndpoint endpoint = WebhookEndpoint.create(
                    id, tenantId, url, command.description(), types, key.keyId(), cipher.encrypt(key.secret(), id.toString()), now);
            endpoints.add(endpoint);
            return new Result(endpoint, key.secret());
        });
    }
}
