package dev.ngb.loyalty.support;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.ngb.core.id.TenantId;
import dev.ngb.core.tenancy.RequestScope;
import dev.ngb.core.tenancy.TenantContext;
import dev.ngb.loyalty.support.EventContracts.Delivery;
import dev.ngb.loyalty.webhook.application.endpoint.command.CreateEndpointUseCase;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A receiver on a loopback port, so a test reads exactly what a Tenant's endpoint would have read.
 *
 * <p>It records every delivery with the headers it arrived with and keeps each endpoint's signing secret, so a case can
 * state one delivery per subscribed endpoint, signed with that endpoint's own secret and carrying the event it names.
 */
public final class WebhookReceiver implements AutoCloseable {

    private final HttpServer server;
    private final TenantContext tenantContext;
    private final CreateEndpointUseCase endpoints;
    private final List<Delivery> deliveries = Collections.synchronizedList(new ArrayList<>());
    private final Map<String, String> secrets = new HashMap<>();

    public WebhookReceiver(TenantContext tenantContext, CreateEndpointUseCase endpoints, String... paths) {
        this.tenantContext = tenantContext;
        this.endpoints = endpoints;
        try {
            this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            for (String path : paths) {
                server.createContext(path, exchange -> record(path, exchange));
            }
            server.start();
        } catch (IOException e) {
            throw new UncheckedIOException("the test receiver could not start", e);
        }
    }

    /** Registers an endpoint of {@code tenant} that subscribes to {@code eventTypes}, and keeps its signing secret. */
    public void subscribe(TenantId tenant, String path, String... eventTypes) {
        CreateEndpointUseCase.Result endpoint = tenantContext.callAs(
                RequestScope.tenant(tenant),
                () -> endpoints.execute(
                        new CreateEndpointUseCase.Command(url(path), null, List.of(eventTypes))));
        secrets.put(path, endpoint.signingSecret());
    }

    public List<Delivery> deliveries() {
        return List.copyOf(deliveries);
    }

    public List<Delivery> of(String eventType) {
        return deliveries.stream().filter(delivery -> eventType.equals(delivery.eventType())).toList();
    }

    /**
     * The one event of {@code eventType} the endpoints read, however many of them subscribed to it: a case that expects
     * two events of one type fails here rather than passing a size assertion for the wrong reason.
     */
    public Delivery only(String eventType) {
        Map<String, Delivery> events = new LinkedHashMap<>();
        for (Delivery delivery : of(eventType)) {
            events.putIfAbsent(delivery.eventId(), delivery);
        }
        assertThat(events.values()).as("exactly one " + eventType + " event").hasSize(1);
        return events.values().iterator().next();
    }

    public void assertSigned(Delivery delivery) {
        EventContracts.assertSigned(secrets.get(delivery.path()), delivery);
    }

    private String url(String path) {
        return "http://127.0.0.1:" + server.getAddress().getPort() + path;
    }

    private void record(String path, HttpExchange exchange) throws IOException {
        byte[] body = exchange.getRequestBody().readAllBytes();
        deliveries.add(new Delivery(
                path,
                exchange.getRequestHeaders().getFirst("Loyalty-Event-Id"),
                exchange.getRequestHeaders().getFirst("Loyalty-Event-Type"),
                exchange.getRequestHeaders().getFirst("Loyalty-Signature"),
                exchange.getRequestHeaders().getFirst("Loyalty-Timestamp"),
                new String(body, StandardCharsets.UTF_8)));
        exchange.sendResponseHeaders(200, -1);
        exchange.close();
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
