package dev.ngb.booking.load;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A small HTTP load runner for the public hold path. It deliberately knows nothing about provisioning: a caller names
 * the base URL, a customer token, an offering and (for capacity) a session, and the runner reports what the gate and the
 * claim engines did. Scale is controlled by {@code BOOKING_LOAD_ATTEMPTS} and {@code BOOKING_LOAD_CONCURRENCY}.
 */
public final class BookingLoadTest {

    public static void main(String[] args) throws Exception {
        String base = env("BOOKING_LOAD_BASE_URL", "http://localhost:8080");
        String token = required("BOOKING_LOAD_TOKEN");
        String offering = required("BOOKING_LOAD_OFFERING");
        String session = env("BOOKING_LOAD_SESSION", "");
        int attempts = Integer.parseInt(env("BOOKING_LOAD_ATTEMPTS", "1000"));
        int concurrency = Integer.parseInt(env("BOOKING_LOAD_CONCURRENCY", "64"));

        String body = session.isBlank()
                ? "{\"offeringId\":\"" + offering + "\"}"
                : "{\"offeringId\":\"" + offering + "\",\"sessionId\":\"" + session
                        + "\",\"poolCode\":\"" + env("BOOKING_LOAD_POOL", "default") + "\",\"qty\":1}";
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(3))
                .build();
        ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor();
        Map<Integer, AtomicInteger> statuses = Collections.synchronizedMap(new TreeMap<>());
        List<Long> latencies = Collections.synchronizedList(new ArrayList<>());
        long started = System.nanoTime();

        try {
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < attempts; i++) {
                futures.add(pool.submit(() -> {
                    long at = System.nanoTime();
                    try {
                        HttpRequest request = HttpRequest.newBuilder(URI.create(base + "/p/bookings"))
                                .timeout(Duration.ofSeconds(10))
                                .header("Authorization", "Bearer " + token)
                                .header("Content-Type", "application/json")
                                .POST(HttpRequest.BodyPublishers.ofString(body))
                                .build();
                        int status = client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
                        statuses.computeIfAbsent(status, key -> new AtomicInteger()).incrementAndGet();
                    } catch (Exception failed) {
                        statuses.computeIfAbsent(-1, key -> new AtomicInteger()).incrementAndGet();
                    } finally {
                        latencies.add((System.nanoTime() - at) / 1_000_000);
                    }
                }));
            }
            for (Future<?> future : futures) {
                future.get();
            }
        } finally {
            pool.shutdown();
        }

        long elapsedMs = (System.nanoTime() - started) / 1_000_000;
        Collections.sort(latencies);
        System.out.printf("attempts=%d elapsedMs=%d throughput=%.1f/s%n",
                attempts, elapsedMs, attempts * 1000.0 / Math.max(1, elapsedMs));
        statuses.forEach((status, count) -> System.out.printf("status %d: %d%n", status, count.get()));
        System.out.printf("hold p50=%dms p95=%dms p99=%dms max=%dms%n",
                percentile(latencies, 50), percentile(latencies, 95), percentile(latencies, 99),
                latencies.isEmpty() ? 0 : latencies.get(latencies.size() - 1));
    }

    private static int percentile(List<Long> sorted, int percentile) {
        if (sorted.isEmpty()) {
            return 0;
        }
        int index = (int) Math.ceil(percentile / 100.0 * sorted.size()) - 1;
        return sorted.get(Math.max(0, Math.min(index, sorted.size() - 1))).intValue();
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }

    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
        return value;
    }

    private BookingLoadTest() {}
}
