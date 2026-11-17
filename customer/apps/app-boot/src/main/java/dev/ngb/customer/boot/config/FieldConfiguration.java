package dev.ngb.customer.boot.config;

import dev.ngb.core.runtime.Clock;
import dev.ngb.core.runtime.UnitOfWork;
import dev.ngb.core.tenancy.TenantContext;
import dev.ngb.customer.customers.adapter.field.json.JacksonFieldValues;
import dev.ngb.customer.customers.adapter.field.persistence.JooqFieldDefinitionReadRepository;
import dev.ngb.customer.customers.adapter.field.persistence.JooqFieldDefinitionRepository;
import dev.ngb.customer.customers.application.field.command.CreateFieldDefinitionUseCase;
import dev.ngb.customer.customers.application.field.command.PatchFieldDefinitionUseCase;
import dev.ngb.customer.customers.application.field.port.FieldDefinitionRepository;
import dev.ngb.customer.customers.application.field.query.FieldDefinitionQueries;
import dev.ngb.customer.customers.application.field.query.FieldDefinitionReadRepository;
import org.jooq.DSLContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the field feature: the definitions the Tenant defines, the values its customers are written with, and the codec
 * between the values and the JSON object they are stored as.
 *
 * <p>The cap on active definitions is read here and passed to the use case, so the application layer never reads a
 * property itself.
 */
@Configuration(proxyBeanMethods = false)
class FieldConfiguration {

    /**
     * How a customer's field values cross into the JSON object they are stored as. The one bean serves both the port the
     * application layer asks for and the codec the repositories use, so a value written and a value read back agree.
     */
    @Bean
    JacksonFieldValues fieldValues() {
        return new JacksonFieldValues();
    }

    @Bean
    FieldDefinitionRepository fieldDefinitionRepository(DSLContext dsl) {
        return new JooqFieldDefinitionRepository(dsl);
    }

    @Bean
    FieldDefinitionReadRepository fieldDefinitionReadRepository(DSLContext dsl) {
        return new JooqFieldDefinitionReadRepository(dsl);
    }

    @Bean
    FieldDefinitionQueries fieldDefinitionQueries(
            UnitOfWork unitOfWork, FieldDefinitionReadRepository definitions) {
        return new FieldDefinitionQueries(unitOfWork, definitions);
    }

    @Bean
    CreateFieldDefinitionUseCase createFieldDefinitionUseCase(
            UnitOfWork unitOfWork,
            FieldDefinitionRepository definitions,
            TenantContext tenantContext,
            Clock clock,
            CustomerSettings settings) {
        return new CreateFieldDefinitionUseCase(
                unitOfWork, definitions, tenantContext, clock, settings.fields().maxActiveDefinitions());
    }

    @Bean
    PatchFieldDefinitionUseCase patchFieldDefinitionUseCase(
            UnitOfWork unitOfWork, FieldDefinitionRepository definitions) {
        return new PatchFieldDefinitionUseCase(unitOfWork, definitions);
    }
}
