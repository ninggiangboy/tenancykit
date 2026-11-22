package dev.ngb.billing.webhook.adapter.delivery.http;

import dev.ngb.billing.webhook.application.delivery.port.WebhookSender;
import dev.ngb.core.webhook.WebhookHttpSender;
import java.io.IOException;
import java.time.Duration;

/**
 * Billing's sender adapter. Every guard a Tenant-chosen destination needs — the address policy, the resolver the
 * connection itself uses, no redirects, bounded time and a bounded response excerpt — lives in
 * {@code core-webhook}. This class maps Billing's request and result records onto the shared ones and keeps the agent
 * Billing has always sent, so extracting the mechanics changes no byte on the wire.
 */
public final class SharedWebhookSender implements WebhookSender, AutoCloseable {

    private static final String USER_AGENT = "Billing-Webhooks/1";

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
