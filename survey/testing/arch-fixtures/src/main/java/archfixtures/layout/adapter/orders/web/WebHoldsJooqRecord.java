package archfixtures.layout.adapter.orders.web;

import org.jooq.DSLContext;

/** Violates web-package-holds-no-persistence: a web class names a jOOQ type. */
public class WebHoldsJooqRecord {
    DSLContext dsl;
}
