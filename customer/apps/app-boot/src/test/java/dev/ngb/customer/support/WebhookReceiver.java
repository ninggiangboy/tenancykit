package dev.ngb.customer.support;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * A local HTTP server that stands in for a Tenant's endpoint: it answers {@code 200} to every request and keeps what it
 * received, so a test asserts what actually left the process rather than what the sender meant to send.
 *
 * <p>The harness allows a non-public target for the same reason: a receiver in a test is a local socket, not an https
 * endpoint on the internet. Each path is a separate endpoint, so one receiver can stand behind two endpoints of one
 * Tenant and behind a second Tenant's, which is how a delivery test proves that a subscription reaches its own
 * endpoint and no other.
 */
public final class WebhookReceiver implements AutoCloseable {

    /** One request as it arrived: the Crm-* headers and the exact body bytes. */
    public record Call(
            String path,
            String eventId,
            String eventType,
            String timestamp,
            String keyId,
            String signature,
            String attempt,
            byte[] body) {

        /** The body as UTF-8 text, which is what the producer wrote. */
        public String bodyText() {
            return new String(body, StandardCharsets.UTF_8);
        }
    }

    private final HttpServer server;
    private final ExecutorService workers;
    private final List<Call> calls = Collections.synchronizedList(new ArrayList<>());

    public WebhookReceiver(String... paths) {
        try {
            this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        this.workers = Executors.newFixedThreadPool(4);
        this.server.setExecutor(workers);
        for (String path : paths) {
            this.server.createContext(path, this::record);
        }
        this.server.start();
    }

    /** The absolute url of one path of this receiver, which is what an endpoint is registered with. */
    public String url(String path) {
        return "http://127.0.0.1:" + server.getAddress().getPort() + path;
    }

    /** What arrived on one path, in arrival order. */
    public List<Call> calls(String path) {
        synchronized (calls) {
            return calls.stream().filter(call -> call.path().equals(path)).toList();
        }
    }

    /** Everything that arrived on every path, for a case that asserts nothing was sent at all. */
    public List<Call> all() {
        synchronized (calls) {
            return List.copyOf(calls);
        }
    }

    private void record(HttpExchange exchange) throws IOException {
        var headers = exchange.getRequestHeaders();
        calls.add(new Call(
                exchange.getRequestURI().getPath(),
                headers.getFirst("Customer-Event-Id"),
                headers.getFirst("Customer-Event-Type"),
                headers.getFirst("Customer-Timestamp"),
                headers.getFirst("Customer-Key-Id"),
                headers.getFirst("Customer-Signature"),
                headers.getFirst("Customer-Attempt"),
                exchange.getRequestBody().readAllBytes()));
        exchange.sendResponseHeaders(200, -1);
        exchange.close();
    }

    @Override
    public void close() {
        server.stop(0);
        workers.shutdownNow();
        try {
            workers.awaitTermination(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
