package dev.ngb.crm.boot.config;

import dev.ngb.core.event.EventPublisher;
import dev.ngb.core.runtime.Clock;
import dev.ngb.core.runtime.IdGenerator;
import dev.ngb.core.runtime.UnitOfWork;
import dev.ngb.core.tenancy.TenantContext;
import dev.ngb.crm.customers.adapter.customer.persistence.JooqCustomerReadRepository;
import dev.ngb.crm.customers.adapter.customer.persistence.JooqCustomerRepository;
import dev.ngb.crm.customers.application.customer.command.PatchCustomerUseCase;
import dev.ngb.crm.customers.application.customer.command.UpsertCustomerUseCase;
import dev.ngb.crm.customers.application.customer.command.UpsertCustomersBatchUseCase;
import dev.ngb.crm.customers.application.customer.port.CustomerRepository;
import dev.ngb.crm.customers.application.customer.query.CustomerQueries;
import dev.ngb.crm.customers.application.customer.query.CustomerReadRepository;
import org.jooq.DSLContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the customers context: its two repositories, the read queries, the single upsert, the patch and the batch.
 *
 * <p>The batch cap is a deployment setting rather than a per-Tenant one, because it bounds one request's work; it is
 * read here and passed to the use case, so the application layer never reads a property itself. The event publisher is
 * the shared outbox appender, which writes inside the calling transaction and therefore inside the transaction that
 * created the customer.
 */
@Configuration(proxyBeanMethods = false)
class CustomerConfiguration {

    @Bean
    CustomerRepository customerRepository(DSLContext dsl) {
        return new JooqCustomerRepository(dsl);
    }

    @Bean
    CustomerReadRepository customerReadRepository(DSLContext dsl) {
        return new JooqCustomerReadRepository(dsl);
    }

    @Bean
    CustomerQueries customerQueries(UnitOfWork unitOfWork, CustomerReadRepository customers) {
        return new CustomerQueries(unitOfWork, customers);
    }

    @Bean
    UpsertCustomerUseCase upsertCustomerUseCase(
            UnitOfWork unitOfWork,
            CustomerRepository customers,
            EventPublisher events,
            TenantContext tenantContext,
            IdGenerator ids,
            Clock clock) {
        return new UpsertCustomerUseCase(unitOfWork, customers, events, tenantContext, ids, clock);
    }

    @Bean
    PatchCustomerUseCase patchCustomerUseCase(UnitOfWork unitOfWork, CustomerRepository customers, Clock clock) {
        return new PatchCustomerUseCase(unitOfWork, customers, clock);
    }

    @Bean
    UpsertCustomersBatchUseCase upsertCustomersBatchUseCase(
            UnitOfWork unitOfWork, UpsertCustomerUseCase upsert, CrmSettings settings) {
        return new UpsertCustomersBatchUseCase(unitOfWork, upsert, settings.batches().maxCustomers());
    }
}
