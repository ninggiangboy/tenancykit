package dev.ngb.workflow.boot.config;

import dev.ngb.core.runtime.Clock;
import dev.ngb.core.runtime.IdGenerator;
import dev.ngb.core.runtime.UnitOfWork;
import dev.ngb.core.tenancy.TenantContext;
import dev.ngb.core.webhook.WebhookHttpSender;
import dev.ngb.workflow.connections.adapter.call.http.GuardedHttpTransport;
import dev.ngb.workflow.connections.adapter.connection.persistence.JooqConnectionRepository;
import dev.ngb.workflow.connections.adapter.credential.crypto.KeyringSecretCipher;
import dev.ngb.workflow.connections.adapter.credential.http.PlatformTokenClient;
import dev.ngb.workflow.connections.adapter.credential.persistence.JooqCredentialRepository;
import dev.ngb.workflow.connections.api.call.HttpCaller;
import dev.ngb.workflow.connections.application.call.port.HttpTransport;
import dev.ngb.workflow.connections.application.call.service.HttpCallerService;
import dev.ngb.workflow.connections.application.connection.command.ManageConnectionUseCase;
import dev.ngb.workflow.connections.application.connection.port.ConnectionRepository;
import dev.ngb.workflow.connections.application.credential.command.RegisterCredentialUseCase;
import dev.ngb.workflow.connections.application.credential.port.CredentialRepository;
import dev.ngb.workflow.connections.application.credential.port.SecretCipher;
import dev.ngb.workflow.connections.application.credential.port.TokenMinter;
import dev.ngb.workflow.connections.application.credential.port.TokenProvider;
import dev.ngb.workflow.connections.application.credential.service.CachingTokenProvider;
import dev.ngb.workflow.runtime.domain.step.OutboundHttp;
import dev.ngb.workflow.shared.secret.SecretBox;
import dev.ngb.workflow.shared.secret.SecretKeyring;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import org.jooq.DSLContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.json.JsonMapper;

/**
 * Wires the connections context: its two stores, the key ring, the token cache and the one outbound door.
 *
 * <p>The transport is the only object that opens a socket to a Tenant-chosen address. The token endpoint is the one
 * exception to the public-address rule: Platform is an internal app, so its address is configured and the guard for it
 * is switched with the deployment, never by the Tenant.
 */
@Configuration(proxyBeanMethods = false)
class ConnectionsConfiguration {

    @Bean
    ConnectionRepository connectionRepository(DSLContext dsl, JsonMapper json) {
        return new JooqConnectionRepository(dsl, json);
    }

    @Bean
    CredentialRepository credentialRepository(DSLContext dsl) {
        return new JooqCredentialRepository(dsl);
    }

    @Bean
    SecretCipher connectionSecretCipher(WorkflowSettings settings) {
        Map<String, SecretBox> boxes = new LinkedHashMap<>();
        String activeId = settings.secrets().keyId();
        boxes.put(activeId, new SecretBox(key(settings.secrets().masterKey()), activeId));
        settings.secrets().previousKeys().forEach((keyId, key) -> boxes.put(keyId, new SecretBox(key(key), keyId)));
        return new KeyringSecretCipher(new SecretKeyring(activeId, boxes));
    }

    @Bean
    TokenMinter tokenMinter(WorkflowSettings settings, JsonMapper json) {
        WorkflowSettings.Connections connections = settings.connections();
        WebhookHttpSender sender = new WebhookHttpSender(
                connections.allowNonPublicTargets(),
                "workflow-runtime",
                20,
                20,
                connections.connectTimeout(),
                connections.connectTimeout(),
                connections.readTimeout(),
                connections.readTimeout().plusSeconds(5));
        return new PlatformTokenClient(sender, connections.tokenUrl(), json);
    }

    @Bean
    TokenProvider tokenProvider(
            CredentialRepository credentials, SecretCipher cipher, TokenMinter minter, Clock clock) {
        return new CachingTokenProvider(credentials, cipher, minter, clock);
    }

    @Bean
    HttpTransport httpTransport(WorkflowSettings settings, JsonMapper json) {
        WorkflowSettings.Connections connections = settings.connections();
        return new GuardedHttpTransport(
                connections.allowNonPublicTargets(),
                "workflow-runtime",
                50,
                20,
                connections.connectTimeout(),
                connections.readTimeout(),
                json);
    }

    @Bean
    HttpCaller httpCaller(
            UnitOfWork unitOfWork,
            ConnectionRepository connections,
            TokenProvider tokens,
            SecretCipher cipher,
            HttpTransport transport,
            TenantContext tenantContext,
            WorkflowSettings settings) {
        return new HttpCallerService(
                unitOfWork,
                connections,
                tokens,
                cipher,
                transport,
                tenantContext,
                settings.connections().apps());
    }

    @Bean
    RegisterCredentialUseCase registerCredential(
            UnitOfWork unitOfWork,
            CredentialRepository credentials,
            SecretCipher cipher,
            TenantContext tenantContext,
            Clock clock) {
        return new RegisterCredentialUseCase(unitOfWork, credentials, cipher, tenantContext, clock);
    }

    @Bean
    ManageConnectionUseCase manageConnection(
            UnitOfWork unitOfWork,
            ConnectionRepository connections,
            SecretCipher cipher,
            TenantContext tenantContext,
            IdGenerator ids,
            Clock clock) {
        return new ManageConnectionUseCase(unitOfWork, connections, cipher, tenantContext, ids, clock);
    }

    @Bean
    OutboundHttp outboundHttp(HttpCaller caller) {
        return new ConnectionHttpAdapter(caller);
    }

    private static byte[] key(String encoded) {
        if (encoded == null || encoded.isBlank()) {
            throw new IllegalStateException(
                    "workflow.secrets.master-key is required: a base64 32-byte AES-256 key");
        }
        return Base64.getDecoder().decode(encoded);
    }
}
