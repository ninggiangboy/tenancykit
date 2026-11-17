package dev.ngb.customer.customers.application.field.command;

import dev.ngb.core.runtime.Clock;
import dev.ngb.core.runtime.UnitOfWork;
import dev.ngb.core.tenancy.TenantContext;
import dev.ngb.customer.customers.application.field.exception.FieldKeyExistsException;
import dev.ngb.customer.customers.application.field.exception.InvalidFieldException;
import dev.ngb.customer.customers.application.field.port.FieldDefinitionRepository;
import dev.ngb.customer.customers.application.field.service.FieldRules;
import dev.ngb.customer.customers.domain.field.FieldDefinition;
import dev.ngb.customer.customers.domain.field.FieldType;
import java.util.List;

/**
 * {@code POST /custom-fields}: the Tenant defines one attribute of its own customers.
 *
 * <p>The key and the type are chosen here and never change, so the values written later under that key mean one thing
 * for as long as it exists. A tenant holds a bounded number of active definitions, and archiving one frees its slot.
 */
public class CreateFieldDefinitionUseCase {

    /** One definition as the Tenant sent it. */
    public record Command(String key, String label, String type, List<String> choices) {}

    private final UnitOfWork unitOfWork;
    private final FieldDefinitionRepository definitions;
    private final TenantContext tenantContext;
    private final Clock clock;
    private final int maxActive;

    public CreateFieldDefinitionUseCase(
            UnitOfWork unitOfWork,
            FieldDefinitionRepository definitions,
            TenantContext tenantContext,
            Clock clock,
            int maxActive) {
        this.unitOfWork = unitOfWork;
        this.definitions = definitions;
        this.tenantContext = tenantContext;
        this.clock = clock;
        this.maxActive = maxActive;
    }

    public FieldDefinition execute(Command command) {
        String key = FieldRules.key(command.key());
        String label = FieldRules.label(command.label());
        FieldType type = FieldRules.type(command.type());
        List<String> choices = FieldRules.choices(type, command.choices());
        return unitOfWork.transactional(() -> {
            if (definitions.countActive() >= maxActive) {
                throw new InvalidFieldException(
                        "This tenant already holds " + maxActive + " active custom fields.");
            }
            FieldDefinition definition =
                    FieldDefinition.define(tenantContext.requireTenant(), key, label, type, choices, clock.now());
            if (!definitions.insertIfAbsent(definition)) {
                throw new FieldKeyExistsException();
            }
            return definition;
        });
    }
}
