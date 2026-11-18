package archfixtures.sharedcore.messaging;

import archfixtures.alpha.domain.Order;

/** Violates core-messaging-is-self-contained: the core's messaging module reaches into an application's module. */
public class CoreMessagingKnowsAnApplication {
    Order order;
}
