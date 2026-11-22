package dev.ngb.customer.customers.application.consent.query;

import java.util.List;

/**
 * What a customer currently agrees to, and how it got there.
 *
 * @param current the latest record of every topic the customer has one for, so a topic it never answered is absent
 * @param history every record of the customer, newest first
 */
public record ConsentsView(List<ConsentRecordView> current, List<ConsentRecordView> history) {

    public ConsentsView {
        current = List.copyOf(current);
        history = List.copyOf(history);
    }
}
