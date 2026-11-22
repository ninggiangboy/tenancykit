package dev.ngb.customer.customers.application.consent.command;

import dev.ngb.core.runtime.UnitOfWork;
import dev.ngb.core.tenancy.TenantContext;
import dev.ngb.customer.customers.application.consent.exception.ConsentTopicKeyExistsException;
import dev.ngb.customer.customers.application.consent.port.ConsentTopicRepository;
import dev.ngb.customer.customers.application.consent.service.ConsentRules;
import dev.ngb.customer.customers.domain.consent.ConsentTopic;

/**
 * {@code POST /consent-topics}: the Tenant names one purpose its customers consent to.
 *
 * <p>The key is chosen here and never changes. Archiving a topic later keeps its records and refuses new ones.
 */
public class CreateConsentTopicUseCase {

    /** One topic as the Tenant sent it. */
    public record Command(String key, String label) {}

    private final UnitOfWork unitOfWork;
    private final ConsentTopicRepository topics;
    private final TenantContext tenantContext;

    public CreateConsentTopicUseCase(
            UnitOfWork unitOfWork, ConsentTopicRepository topics, TenantContext tenantContext) {
        this.unitOfWork = unitOfWork;
        this.topics = topics;
        this.tenantContext = tenantContext;
    }

    public ConsentTopic execute(Command command) {
        String key = ConsentRules.key(command.key());
        String label = ConsentRules.label(command.label());
        return unitOfWork.transactional(() -> {
            ConsentTopic topic = ConsentTopic.define(tenantContext.requireTenant(), key, label);
            if (!topics.insertIfAbsent(topic)) {
                throw new ConsentTopicKeyExistsException();
            }
            return topic;
        });
    }
}
