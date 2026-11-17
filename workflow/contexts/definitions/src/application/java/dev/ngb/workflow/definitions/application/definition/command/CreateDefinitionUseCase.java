package dev.ngb.workflow.definitions.application.definition.command;

import dev.ngb.core.error.ConflictException;
import dev.ngb.core.runtime.Clock;
import dev.ngb.core.runtime.IdGenerator;
import dev.ngb.core.runtime.UnitOfWork;
import dev.ngb.core.tenancy.TenantContext;
import dev.ngb.workflow.definitions.application.definition.port.DefinitionRepository;
import dev.ngb.workflow.definitions.application.definition.service.DefinitionRules;
import dev.ngb.workflow.definitions.domain.definition.Definition;
import java.time.Instant;

/** Creates a definition with an empty draft; the graph arrives through the draft endpoint. */
public class CreateDefinitionUseCase {

    public record Command(String code, String name) {}

    private final UnitOfWork unitOfWork;
    private final DefinitionRepository definitions;
    private final DefinitionRules rules;
    private final TenantContext tenantContext;
    private final IdGenerator ids;
    private final Clock clock;

    public CreateDefinitionUseCase(
            UnitOfWork unitOfWork,
            DefinitionRepository definitions,
            DefinitionRules rules,
            TenantContext tenantContext,
            IdGenerator ids,
            Clock clock) {
        this.unitOfWork = unitOfWork;
        this.definitions = definitions;
        this.rules = rules;
        this.tenantContext = tenantContext;
        this.ids = ids;
        this.clock = clock;
    }

    public Definition execute(Command command) {
        String code = rules.code(command.code());
        String name = rules.name(command.name());
        return unitOfWork.transactional(() -> {
            if (definitions.findByCode(code).isPresent()) {
                throw new ConflictException("a definition with this code already exists here");
            }
            Instant now = clock.now();
            Definition definition =
                    Definition.create(ids.newId(), tenantContext.requireTenant(), code, name, now);
            definitions.add(definition);
            return definition;
        });
    }
}
