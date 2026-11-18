package dev.ngb.customer.customers.application.consent.command;

import dev.ngb.core.runtime.UnitOfWork;
import dev.ngb.customer.customers.application.consent.exception.ConsentTopicNotFoundException;
import dev.ngb.customer.customers.application.consent.port.ConsentTopicRepository;
import dev.ngb.customer.customers.application.consent.service.ConsentRules;
import dev.ngb.customer.customers.domain.consent.ConsentTopic;

/**
 * {@code PATCH /consent-topics/{key}}: the label, or whether the topic is archived. A null property is left as it is,
 * and an unchanged one writes nothing. Archiving keeps every record already made and refuses new ones.
 */
public class PatchConsentTopicUseCase {

    /** The properties to change: a null one is left as it is. */
    public record Change(String label, Boolean archived) {}

    private final UnitOfWork unitOfWork;
    private final ConsentTopicRepository topics;

    public PatchConsentTopicUseCase(UnitOfWork unitOfWork, ConsentTopicRepository topics) {
        this.unitOfWork = unitOfWork;
        this.topics = topics;
    }

    public ConsentTopic execute(String rawKey, Change change) {
        String key = ConsentRules.key(rawKey);
        return unitOfWork.transactional(() -> {
            ConsentTopic topic = topics.lock(key).orElseThrow(ConsentTopicNotFoundException::new);
            String label = change.label() == null ? null : ConsentRules.label(change.label());
            if (topic.change(label, change.archived())) {
                topics.update(topic);
            }
            return topic;
        });
    }
}
