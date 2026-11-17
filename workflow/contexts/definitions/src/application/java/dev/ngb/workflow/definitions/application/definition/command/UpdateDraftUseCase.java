package dev.ngb.workflow.definitions.application.definition.command;

import dev.ngb.core.error.NotFoundException;
import dev.ngb.core.runtime.Clock;
import dev.ngb.core.runtime.UnitOfWork;
import dev.ngb.core.version.ExpectedVersion;
import dev.ngb.workflow.definitions.application.definition.port.DefinitionRepository;
import dev.ngb.workflow.definitions.application.definition.service.DefinitionRules;
import dev.ngb.workflow.definitions.domain.definition.Definition;
import dev.ngb.workflow.definitions.domain.problem.Problem;
import java.util.List;
import java.util.UUID;

/**
 * Replaces the draft graph under {@code If-Match}. A structurally wrong draft is stored as sent and its problems are
 * returned, because a draft is what an author is still writing; only publish refuses one.
 */
public class UpdateDraftUseCase {

    public record Command(UUID definitionId, Object graph, ExpectedVersion expected) {}

    public record Result(Definition definition, List<Problem> problems) {}

    private final UnitOfWork unitOfWork;
    private final DefinitionRepository definitions;
    private final DefinitionRules rules;
    private final Clock clock;

    public UpdateDraftUseCase(
            UnitOfWork unitOfWork, DefinitionRepository definitions, DefinitionRules rules, Clock clock) {
        this.unitOfWork = unitOfWork;
        this.definitions = definitions;
        this.rules = rules;
        this.clock = clock;
    }

    public Result execute(Command command) {
        return unitOfWork.transactional(() -> {
            Definition definition = definitions
                    .lockById(command.definitionId())
                    .orElseThrow(() -> new NotFoundException("No workflow definition exists here."));
            command.expected().check(definition.version());
            definition.replaceDraft(command.graph(), clock.now());
            definitions.update(definition);
            return new Result(definition, rules.problems(definition.draft()));
        });
    }
}
