package dev.ngb.crm.customers.application.tag.query;

/** One tag the tenant's customers carry, with the number of customers that carry it. */
public record TagView(String tag, long customerCount) {}
