package archfixtures.sharedcore.feign;

import archfixtures.alpha.domain.Order;

/** Violates core-feign-is-self-contained: the shared HTTP client reaches into an application's module. */
public class CoreFeignKnowsAnApplication {
    Order order;
}
