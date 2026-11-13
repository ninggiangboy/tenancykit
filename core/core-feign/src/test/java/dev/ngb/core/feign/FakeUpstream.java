package dev.ngb.core.feign;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** A local HTTP server standing in for an upstream, recording every request and answering from a queue. */
final class FakeUpstream implements AutoCloseable {

    record Request(String method, String path, Map<String, List<String>> headers, String body) {

        String header(String name) {
            return headers.entrySet().stream()
                    .filter(entry -> entry.getKey().equalsIgnoreCase(name))
                    .map(entry -> entry.getValue().get(0))
                    .findFirst()
                    .orElse(null);
        }
    }

    private record Answer(int status, String body, Duration delay) {}

    private final HttpServer server;
    private final List<Request> requests = new ArrayList<>();
    private final List<Answer> answers = new ArrayList<>();

    FakeUpstream() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.start();
    }

    void respond(int status, String body) {
        answers.add(new Answer(status, body, Duration.ZERO));
    }

    void respondAfter(Duration delay, int status, String body) {
        answers.add(new Answer(status, body, delay));
    }

    List<Request> requests() {
        return requests;
    }

    Request request(int index) {
        return requests.get(index);
    }

    String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private void handle(HttpExchange exchange) throws IOException {
        byte[] body = exchange.getRequestBody().readAllBytes();
        synchronized (this) {
            requests.add(new Request(
                    exchange.getRequestMethod(),
                    exchange.getRequestURI().getRawPath(),
                    new LinkedHashMap<>(exchange.getRequestHeaders()),
                    new String(body, StandardCharsets.UTF_8)));
        }
        Answer answer;
        synchronized (this) {
            answer = answers.isEmpty() ? new Answer(500, "{}", Duration.ZERO) : answers.remove(0);
        }
        if (!answer.delay().isZero()) {
            try {
                Thread.sleep(answer.delay().toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        byte[] payload = answer.body().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(answer.status(), payload.length);
        exchange.getResponseBody().write(payload);
        exchange.close();
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
