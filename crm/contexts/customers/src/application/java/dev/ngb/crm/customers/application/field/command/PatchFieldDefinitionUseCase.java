package dev.ngb.crm.customers.application.field.command;

import dev.ngb.core.runtime.UnitOfWork;
import dev.ngb.crm.customers.application.field.exception.CustomFieldNotFoundException;
import dev.ngb.crm.customers.application.field.exception.FieldTypeImmutableException;
import dev.ngb.crm.customers.application.field.port.FieldDefinitionRepository;
import dev.ngb.crm.customers.application.field.service.FieldRules;
import dev.ngb.crm.customers.domain.field.FieldDefinition;
import dev.ngb.crm.customers.domain.field.FieldType;
import java.util.List;

/**
 * {@code PATCH /custom-fields/{key}}: the label, the choices a {@code CHOICE} accepts, or whether the definition is
 * archived. A null property is left as it is, and an unchanged one writes nothing.
 *
 * <p>The key and the type are refused when the request names another one: the values already stored were written under
 * the old type, so changing it would change what they mean.
 */
public class PatchFieldDefinitionUseCase {

    /** The properties to change: a null one is left as it is. */
    public record Change(String label, List<String> choices, Boolean archived, String type) {}

    private final UnitOfWork unitOfWork;
    private final FieldDefinitionRepository definitions;

    public PatchFieldDefinitionUseCase(UnitOfWork unitOfWork, FieldDefinitionRepository definitions) {
        this.unitOfWork = unitOfWork;
        this.definitions = definitions;
    }

    public FieldDefinition execute(String rawKey, Change change) {
        String key = FieldRules.key(rawKey);
        return unitOfWork.transactional(() -> {
            FieldDefinition definition =
                    definitions.lock(key).orElseThrow(CustomFieldNotFoundException::new);
            requireSameType(definition, change.type());
            String label = change.label() == null ? null : FieldRules.label(change.label());
            List<String> added = FieldRules.addedChoices(definition, change.choices());
            if (definition.change(label, added, change.archived())) {
                definitions.update(definition);
            }
            return definition;
        });
    }

    /** A type change is a new field, never an edit: the answer is a conflict rather than a silent refusal. */
    private static void requireSameType(FieldDefinition definition, String requested) {
        if (requested == null) {
            return;
        }
        FieldType type = FieldRules.type(requested);
        if (type != definition.type()) {
            throw new FieldTypeImmutableException();
        }
    }
}
