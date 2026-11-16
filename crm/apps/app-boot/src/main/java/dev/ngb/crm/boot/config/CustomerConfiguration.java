package dev.ngb.crm.boot.config;

import dev.ngb.core.event.EventPublisher;
import dev.ngb.core.runtime.Clock;
import dev.ngb.core.runtime.IdGenerator;
import dev.ngb.core.runtime.UnitOfWork;
import dev.ngb.core.tenancy.TenantContext;
import dev.ngb.crm.boot.export.TenantExportLease;
import dev.ngb.crm.customers.adapter.customer.persistence.CustomerSearchConditions;
import dev.ngb.crm.customers.adapter.customer.persistence.JooqCustomerDirectory;
import dev.ngb.crm.customers.adapter.customer.persistence.JooqCustomerExportReadRepository;
import dev.ngb.crm.customers.adapter.customer.persistence.JooqCustomerProfileReader;
import dev.ngb.crm.customers.adapter.customer.persistence.JooqCustomerReadRepository;
import dev.ngb.crm.customers.adapter.customer.persistence.JooqCustomerRepository;
import dev.ngb.crm.customers.adapter.field.json.JacksonFieldValues;
import dev.ngb.crm.customers.api.customer.CustomerDirectory;
import dev.ngb.crm.customers.api.customer.CustomerProfileReader;
import dev.ngb.crm.customers.application.customer.command.PatchCustomerUseCase;
import dev.ngb.crm.customers.application.customer.command.UpsertCustomerUseCase;
import dev.ngb.crm.customers.application.customer.command.UpsertCustomersBatchUseCase;
import dev.ngb.crm.customers.application.customer.port.CustomerRepository;
import dev.ngb.crm.customers.application.customer.query.ExportLease;
import dev.ngb.crm.customers.application.customer.query.CustomerExportReadRepository;
import dev.ngb.crm.customers.application.customer.query.CustomerExporter;
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

    /**
     * The context's contract for the contexts that record something about a customer. It reads through the context's
     * own repository and creates through the same upsert a {@code PUT} takes, so both paths announce a customer alike.
     */
    @Bean
    CustomerDirectory customerDirectory(CustomerRepository customers, UpsertCustomerUseCase upsert) {
        return new JooqCustomerDirectory(customers, upsert);
    }

    /** The search terms the customer list and the export share, turned into predicates in one place. */
    @Bean
    CustomerSearchConditions customerSearchConditions(DSLContext dsl) {
        return new CustomerSearchConditions(dsl);
    }

    @Bean
    CustomerReadRepository customerReadRepository(
            DSLContext dsl, JacksonFieldValues fieldValues, CustomerSearchConditions searchConditions) {
        return new JooqCustomerReadRepository(dsl, fieldValues, searchConditions);
    }

    /** One customer read whole, which is what the profile context composes its document from. */
    @Bean
    CustomerProfileReader customerProfileReader(DSLContext dsl, JacksonFieldValues fieldValues) {
        return new JooqCustomerProfileReader(dsl, fieldValues);
    }

    @Bean
    CustomerExportReadRepository customerExportReadRepository(
            DSLContext dsl, JacksonFieldValues fieldValues, CustomerSearchConditions searchConditions) {
        return new JooqCustomerExportReadRepository(dsl, fieldValues, searchConditions);
    }

    /**
     * One export per tenant: the claim is a lease in the coordination table every instance reads, so a second request
     * is refused and a process that died mid-export stops blocking its tenant when the lease runs out.
     */
    @Bean
    ExportLease exportLease(
            DSLContext dsl, UnitOfWork unitOfWork, TenantContext tenantContext, Clock clock, CrmSettings settings) {
        return new TenantExportLease(dsl, unitOfWork, tenantContext, clock, settings.exports().lease());
    }

    @Bean
    CustomerExporter customerExporter(
            UnitOfWork unitOfWork,
            CustomerExportReadRepository customers,
            ExportLease leases,
            CrmSettings settings) {
        return new CustomerExporter(unitOfWork, customers, leases, settings.exports().pageSize());
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
