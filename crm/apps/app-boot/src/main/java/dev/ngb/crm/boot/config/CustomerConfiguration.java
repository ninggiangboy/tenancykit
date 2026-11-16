package dev.ngb.crm.boot.config;

import dev.ngb.core.event.EventPublisher;
import dev.ngb.core.runtime.Clock;
import dev.ngb.core.runtime.IdGenerator;
import dev.ngb.core.runtime.UnitOfWork;
import dev.ngb.core.tenancy.TenantContext;
import dev.ngb.crm.customers.adapter.customer.persistence.JooqCustomerReadRepository;
import dev.ngb.crm.customers.adapter.customer.persistence.JooqCustomerRepository;
import dev.ngb.crm.customers.adapter.field.json.JacksonFieldValues;
import dev.ngb.crm.customers.application.customer.command.PatchCustomerUseCase;
import dev.ngb.crm.customers.application.customer.command.UpsertCustomerUseCase;
import dev.ngb.crm.customers.application.customer.command.UpsertCustomersBatchUseCase;
import dev.ngb.crm.customers.application.customer.port.CustomerRepository;
import dev.ngb.crm.customers.application.customer.query.CustomerQueries;
import dev.ngb.crm.customers.application.customer.query.CustomerReadRepository;
import dev.ngb.crm.customers.application.customer.service.CustomerFields;
import dev.ngb.crm.customers.application.field.port.FieldDefinitionRepository;
import dev.ngb.crm.customers.application.field.port.FieldValues;
import org.jooq.DSLContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the customers context: its two repositories, the read queries, the single upsert, the patch and the batch.
 *
 * <p>The batch cap is a deployment setting rather than a per-Tenant one, because it bounds one request's work; it is
 * read here and passed to the use case, so the application layer never reads a property itself. The tag cap and the
 * field-value cap are passed the same way: they bound what one customer may hold. The event publisher is the shared
 * outbox appender, which writes inside the calling transaction and therefore inside the transaction that created the
 * customer.
 */
@Configuration(proxyBeanMethods = false)
class CustomerConfiguration {

    @Bean
    CustomerRepository customerRepository(DSLContext dsl, JacksonFieldValues fieldValues) {
        return new JooqCustomerRepository(dsl, fieldValues);
    }

    @Bean
    CustomerReadRepository customerReadRepository(DSLContext dsl, JacksonFieldValues fieldValues) {
        return new JooqCustomerReadRepository(dsl, fieldValues);
    }

    @Bean
    CustomerQueries customerQueries(UnitOfWork unitOfWork, CustomerReadRepository customers) {
        return new CustomerQueries(unitOfWork, customers);
    }

    /** The field values of one customer, checked against the tenant's definitions before they are stored. */
    @Bean
    CustomerFields customerFields(
            FieldDefinitionRepository definitions, FieldValues fieldValues, CrmSettings settings) {
        return new CustomerFields(definitions, fieldValues, settings.fields().maxBytes());
    }

    @Bean
    UpsertCustomerUseCase upsertCustomerUseCase(
            UnitOfWork unitOfWork,
            CustomerRepository customers,
            CustomerFields customerFields,
            EventPublisher events,
            TenantContext tenantContext,
            IdGenerator ids,
            Clock clock,
            CrmSettings settings) {
        return new UpsertCustomerUseCase(
                unitOfWork,
                customers,
                customerFields,
                events,
                tenantContext,
                ids,
                clock,
                settings.tags().maxPerCustomer());
    }

    @Bean
    PatchCustomerUseCase patchCustomerUseCase(
            UnitOfWork unitOfWork,
            CustomerRepository customers,
            CustomerFields customerFields,
            Clock clock,
            CrmSettings settings) {
        return new PatchCustomerUseCase(
                unitOfWork, customers, customerFields, clock, settings.tags().maxPerCustomer());
    }

    @Bean
    UpsertCustomersBatchUseCase upsertCustomersBatchUseCase(
            UnitOfWork unitOfWork, UpsertCustomerUseCase upsert, CrmSettings settings) {
        return new UpsertCustomersBatchUseCase(unitOfWork, upsert, settings.batches().maxCustomers());
    }
}
