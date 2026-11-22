package dev.ngb.loyalty.webhook.adapter.delivery.http;

import dev.ngb.loyalty.webhook.application.delivery.port.WebhookSender;
import dev.ngb.core.webhook.WebhookHttpSender;
import java.io.IOException;
import java.time.Duration;

/**
 * Loyalty's sender adapter. Every guard a Tenant-chosen destination needs — the address policy, the resolver the
 * connection itself uses, no redirects, bounded time and a bounded response excerpt — lives in {@code core-webhook};
 * this class only names the app's own user agent.
 */
public final class SharedWebhookSender implements WebhookSender, AutoCloseable {

    private static final String USER_AGENT = "Loyalty-Webhooks/1";

    private final WebhookHttpSender sender;

    public SharedWebhookSender(
            boolean allowNonPublicTargets, int maxConnTotal, int maxConnPerRoute, Duration connectTimeout,
            Duration connectionRequestTimeout, Duration readTimeout, Duration deadline) {
        this.sender = new WebhookHttpSender(
                allowNonPublicTargets, USER_AGENT, maxConnTotal, maxConnPerRoute, connectTimeout, connectionRequestTimeout,
                readTimeout, deadline);
    }

    @Override
    public Result send(Request request) {
        WebhookHttpSender.Result result =
                sender.send(new WebhookHttpSender.Request(request.url(), request.headers(), request.body()));
        return new Result(result.statusCode(), result.error(), result.responseExcerpt(), result.latencyMs());
    }

    @Override
    public void close() throws IOException {
        sender.close();
    }
}
