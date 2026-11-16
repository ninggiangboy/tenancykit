package dev.ngb.crm.boot.config;

import dev.ngb.core.runtime.Clock;
import dev.ngb.core.runtime.IdGenerator;
import dev.ngb.core.runtime.UnitOfWork;
import dev.ngb.core.tenancy.TenantContext;
import dev.ngb.crm.customers.adapter.consent.persistence.JooqConsentEventRepository;
import dev.ngb.crm.customers.adapter.consent.persistence.JooqConsentReadRepository;
import dev.ngb.crm.customers.adapter.consent.persistence.JooqConsentTopicReadRepository;
import dev.ngb.crm.customers.adapter.consent.persistence.JooqConsentTopicRepository;
import dev.ngb.crm.customers.application.consent.command.CreateConsentTopicUseCase;
import dev.ngb.crm.customers.application.consent.command.PatchConsentTopicUseCase;
import dev.ngb.crm.customers.application.consent.command.RecordConsentUseCase;
import dev.ngb.crm.customers.application.consent.port.ConsentEventRepository;
import dev.ngb.crm.customers.application.consent.port.ConsentTopicRepository;
import dev.ngb.crm.customers.application.consent.query.ConsentQueries;
import dev.ngb.crm.customers.application.consent.query.ConsentReadRepository;
import dev.ngb.crm.customers.application.consent.query.ConsentTopicQueries;
import dev.ngb.crm.customers.application.consent.query.ConsentTopicReadRepository;
import dev.ngb.crm.customers.application.customer.port.CustomerRepository;
import org.jooq.DSLContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the consent feature: the topics the Tenant defines and the append-only records its customers' agreements are
 * made of.
 *
 * <p>The record use case reads the customer through the customers context's own port, because a consent belongs to a
 * customer that must exist before it can be given. The bound on how far ahead an {@code occurredAt} may be is read
 * here, so the application layer never reads a property itself.
 */
@Configuration(proxyBeanMethods = false)
class ConsentConfiguration {

    @Bean
    ConsentTopicRepository consentTopicRepository(DSLContext dsl) {
        return new JooqConsentTopicRepository(dsl);
    }

    @Bean
    ConsentEventRepository consentEventRepository(DSLContext dsl) {
        return new JooqConsentEventRepository(dsl);
    }

    @Bean
    ConsentTopicReadRepository consentTopicReadRepository(DSLContext dsl) {
        return new JooqConsentTopicReadRepository(dsl);
    }

    @Bean
    ConsentReadRepository consentReadRepository(DSLContext dsl) {
        return new JooqConsentReadRepository(dsl);
    }

    @Bean
    ConsentTopicQueries consentTopicQueries(UnitOfWork unitOfWork, ConsentTopicReadRepository topics) {
        return new ConsentTopicQueries(unitOfWork, topics);
    }

    @Bean
    ConsentQueries consentQueries(UnitOfWork unitOfWork, ConsentReadRepository consents) {
        return new ConsentQueries(unitOfWork, consents);
    }

    @Bean
    CreateConsentTopicUseCase createConsentTopicUseCase(
            UnitOfWork unitOfWork, ConsentTopicRepository topics, TenantContext tenantContext) {
        return new CreateConsentTopicUseCase(unitOfWork, topics, tenantContext);
    }

    @Bean
    PatchConsentTopicUseCase patchConsentTopicUseCase(
            UnitOfWork unitOfWork, ConsentTopicRepository topics) {
        return new PatchConsentTopicUseCase(unitOfWork, topics);
    }

    @Bean
    RecordConsentUseCase recordConsentUseCase(
            UnitOfWork unitOfWork,
            CustomerRepository customers,
            ConsentTopicRepository topics,
            ConsentEventRepository events,
            IdGenerator ids,
            Clock clock,
            CrmSettings settings) {
        return new RecordConsentUseCase(
                unitOfWork,
                customers,
                topics,
                events,
                ids,
                clock,
                settings.consents().maxFutureSkew());
    }
}
