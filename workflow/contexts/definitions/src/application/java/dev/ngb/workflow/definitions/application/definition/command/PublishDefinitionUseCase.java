package dev.ngb.workflow.definitions.application.definition.command;

import dev.ngb.core.error.NotFoundException;
import dev.ngb.core.runtime.Clock;
import dev.ngb.core.runtime.IdGenerator;
import dev.ngb.core.runtime.UnitOfWork;
import dev.ngb.core.version.ExpectedVersion;
import dev.ngb.workflow.definitions.application.definition.exception.DefinitionInvalidException;
import dev.ngb.workflow.definitions.application.definition.port.DefinitionRepository;
import dev.ngb.workflow.definitions.application.definition.port.VersionRepository;
import dev.ngb.workflow.definitions.application.definition.service.DefinitionRules;
import dev.ngb.workflow.definitions.domain.definition.ContentHash;
import dev.ngb.workflow.definitions.domain.definition.Definition;
import dev.ngb.workflow.definitions.domain.definition.PublishedVersion;
import dev.ngb.workflow.definitions.domain.expression.Json;
import dev.ngb.workflow.definitions.domain.problem.Problem;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Publishes the current draft as the next immutable version. The definition row is locked while its number is chosen,
 * so two publishes cannot take the same number, and an invalid draft is refused with every problem at once.
 */
public class PublishDefinitionUseCase {

    public record Command(UUID definitionId, ExpectedVersion expected, String publishedBy) {}

    public record Result(Definition definition, PublishedVersion version) {}

    private final UnitOfWork unitOfWork;
    private final DefinitionRepository definitions;
    private final VersionRepository versions;
    private final DefinitionRules rules;
    private final IdGenerator ids;
    private final Clock clock;

    public PublishDefinitionUseCase(
            UnitOfWork unitOfWork,
            DefinitionRepository definitions,
            VersionRepository versions,
            DefinitionRules rules,
            IdGenerator ids,
            Clock clock) {
        this.unitOfWork = unitOfWork;
        this.definitions = definitions;
        this.versions = versions;
        this.rules = rules;
        this.ids = ids;
        this.clock = clock;
    }

    public Result execute(Command command) {
        return unitOfWork.transactional(() -> {
            Definition definition = definitions
                    .lockById(command.definitionId())
                    .orElseThrow(() -> new NotFoundException("No workflow definition exists here."));
            command.expected().check(definition.version());
            Object draft = definition.draft();
            List<Problem> problems = rules.problems(draft);
            if (!problems.isEmpty()) {
                throw new DefinitionInvalidException(problems);
            }
            Instant now = clock.now();
            int number = versionNumber(definition);
            PublishedVersion version = new PublishedVersion(
                    ids.newId(),
                    definition.tenantId(),
                    definition.id(),
                    number,
                    Json.copy(draft),
                    ContentHash.of(draft),
                    now,
                    command.publishedBy());
            versions.add(version);
            definition.publish(number, now);
            definitions.update(definition);
            return new Result(definition, version);
        });
    }

    private int versionNumber(Definition definition) {
        return definition.latestVersion() + 1;
    }
}
