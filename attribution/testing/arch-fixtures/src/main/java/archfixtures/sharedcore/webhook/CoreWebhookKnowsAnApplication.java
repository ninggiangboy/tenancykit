package archfixtures.sharedcore.webhook;

import archfixtures.alpha.domain.Order;

/** Violates core-webhook-is-self-contained: the shared webhook module reaches into an application's module. */
public class CoreWebhookKnowsAnApplication {
    Order order;
}
