package archfixtures.orders.application.command;

import archfixtures.webhook.domain.endpoint.WebhookSubscription;

/** Violates business-contexts-do-not-import-webhook: a business context names the webhook context. */
public class OrderKnowsAWebhook {

    public String url(WebhookSubscription subscription) {
        return subscription.url();
    }
}
