package dev.ngb.customer.customers.application.consent.query;

/** One consent topic as a read returns it. */
public record ConsentTopicView(String key, String label, boolean archived) {}
