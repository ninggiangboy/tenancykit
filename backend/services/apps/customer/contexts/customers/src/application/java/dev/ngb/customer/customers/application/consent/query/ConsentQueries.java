package dev.ngb.customer.customers.application.consent.query;

import dev.ngb.core.error.NotFoundException;
import dev.ngb.core.runtime.UnitOfWork;
import dev.ngb.customer.customers.application.customer.service.CustomerRules;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Reads what a customer currently agrees to and the history behind it. */
public class ConsentQueries {

    private final UnitOfWork unitOfWork;
    private final ConsentReadRepository consents;

    public ConsentQueries(UnitOfWork unitOfWork, ConsentReadRepository consents) {
        this.unitOfWork = unitOfWork;
        this.consents = consents;
    }

    /** @throws NotFoundException when the tenant holds no customer with that reference */
    public ConsentsView read(String externalRef) {
        String ref = CustomerRules.externalRef(externalRef);
        return unitOfWork.transactional(() -> {
            UUID customerId = consents
                    .findCustomerId(ref)
                    .orElseThrow(() -> new NotFoundException("No customer exists here with that reference."));
            List<ConsentRecordView> history = consents.history(customerId);
            return new ConsentsView(current(history), history);
        });
    }

    /**
     * The current state of every topic: the first record of that topic in a history ordered by what happened. A topic
     * the customer never answered is absent, which is not the same answer as a revoked one.
     */
    private static List<ConsentRecordView> current(List<ConsentRecordView> historyNewestFirst) {
        Map<String, ConsentRecordView> byTopic = new LinkedHashMap<>();
        for (ConsentRecordView record : historyNewestFirst) {
            byTopic.putIfAbsent(record.topicKey(), record);
        }
        return byTopic.values().stream()
                .sorted((left, right) -> left.topicKey().compareTo(right.topicKey()))
                .toList();
    }
}
