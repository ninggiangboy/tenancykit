package archfixtures.sharedcore.visitor;

import archfixtures.alpha.domain.Order;

/** Violates core-visitor-is-self-contained: the shared visitor module reaches into an application's module. */
public class CoreVisitorKnowsAnApplication {
    Order order;
}
