package dev.ngb.workflow.definitions.application.definition.command;

import dev.ngb.core.error.NotFoundException;
import dev.ngb.core.runtime.Clock;
import dev.ngb.core.runtime.UnitOfWork;
import dev.ngb.core.version.ExpectedVersion;
import dev.ngb.workflow.definitions.application.definition.port.DefinitionRepository;
import dev.ngb.workflow.definitions.domain.definition.Definition;
import java.util.UUID;

/** Retires a published definition: no new instance starts from it, and existing instances keep running. */
public class RetireDefinitionUseCase {

    public record Command(UUID definitionId, ExpectedVersion expected) {}

    private final UnitOfWork unitOfWork;
    private final DefinitionRepository definitions;
    private final Clock clock;

    public RetireDefinitionUseCase(UnitOfWork unitOfWork, DefinitionRepository definitions, Clock clock) {
        this.unitOfWork = unitOfWork;
        this.definitions = definitions;
        this.clock = clock;
    }

    public Definition execute(Command command) {
        return unitOfWork.transactional(() -> {
            Definition definition = definitions
                    .lockById(command.definitionId())
                    .orElseThrow(() -> new NotFoundException("No workflow definition exists here."));
            command.expected().check(definition.version());
            definition.retire(clock.now());
            definitions.update(definition);
            return definition;
        });
    }
}
