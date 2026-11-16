package dev.ngb.crm.webhook.application.endpoint.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.ngb.core.webhook.DestinationPolicy;
import dev.ngb.crm.webhook.application.endpoint.port.EventTypeCatalog;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * The rules an endpoint obeys: its URL must be a public HTTPS destination, and its types must be ones crm produces.
 *
 * <p>The address check that blocks a loopback or cloud-metadata target lives in {@code core-webhook}'s destination
 * policy, which runs when a delivery is actually sent; an endpoint whose host resolves privately fails then rather than
 * at creation, so a DNS record that changes later is re-checked on every send.
 */
class EndpointRulesTest {

    private static final EventTypeCatalog CATALOG = () -> Set.of(
            "customer.created", "order.recorded", "order.refunded", "customer.erased");

    @Test
    void everyProducedTypeAndTheWildcardAreAccepted() {
        assertThat(EndpointRules.eventTypes(List.of("customer.created", "order.recorded"), CATALOG))
                .containsExactlyInAnyOrder("customer.created", "order.recorded");
        assertThat(EndpointRules.eventTypes(List.of("*"), CATALOG)).containsExactly("*");
    }

    @Test
    void aTypeTheAppDoesNotProduceIsRefused() {
        assertThatThrownBy(() -> EndpointRules.eventTypes(List.of("points.earned"), CATALOG))
                .as("a Tenant cannot subscribe to another app's event")
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void anEmptySubscriptionIsRefused() {
        assertThatThrownBy(() -> EndpointRules.eventTypes(List.of(), CATALOG))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void anInsecureOrCredentialedUrlIsRefused() {
        WebhookSettings settings = WebhookSettings.strictForTests();
        assertThatThrownBy(() -> EndpointRules.url("http://hooks.example.com/x", settings))
                .as("a webhook must use https")
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> EndpointRules.url("https://user:pass@hooks.example.com/x", settings))
                .as("a URL must not carry credentials")
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aPublicHttpsUrlIsAccepted() {
        assertThat(EndpointRules.url("https://hooks.example.com/crm", WebhookSettings.strictForTests()))
                .isEqualTo("https://hooks.example.com/crm");
    }

    @Test
    void theDestinationPolicyTreatsLoopbackAndCloudMetadataAsNonPublic() throws Exception {
        assertThat(DestinationPolicy.isPublic(java.net.InetAddress.getByName("127.0.0.1")))
                .as("loopback is refused, so a webhook cannot reach the suite's own services")
                .isFalse();
        assertThat(DestinationPolicy.isPublic(java.net.InetAddress.getByName("169.254.169.254")))
                .as("the cloud metadata address is refused")
                .isFalse();
        assertThat(DestinationPolicy.isPublic(java.net.InetAddress.getByName("93.184.216.34")))
                .as("a public address is allowed")
                .isTrue();
    }
}
