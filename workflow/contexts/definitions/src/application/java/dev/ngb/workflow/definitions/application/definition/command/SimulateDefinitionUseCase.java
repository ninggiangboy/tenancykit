package dev.ngb.workflow.definitions.application.definition.command;

import dev.ngb.core.error.NotFoundException;
import dev.ngb.core.runtime.UnitOfWork;
import dev.ngb.workflow.definitions.application.definition.port.DefinitionRepository;
import dev.ngb.workflow.definitions.application.definition.service.DefinitionRules;
import dev.ngb.workflow.definitions.application.definition.service.GraphSimulation;
import dev.ngb.workflow.definitions.domain.definition.Definition;
import dev.ngb.workflow.definitions.domain.definition.Graph;
import dev.ngb.workflow.definitions.domain.definition.GraphParser;
import dev.ngb.workflow.definitions.domain.problem.Problem;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Walks the draft over a sample payload and reports the path, the decisions and the variables; it never writes. */
public class SimulateDefinitionUseCase {

    public record Command(UUID definitionId, Map<String, Object> input, Map<String, Object> steps) {}

    public record Result(
            List<String> path,
            List<GraphSimulation.Decision> decisions,
            Map<String, Object> variables,
            String stoppedAt,
            List<Problem> problems) {}

    private final UnitOfWork unitOfWork;
    private final DefinitionRepository definitions;
    private final DefinitionRules rules;
    private final GraphSimulation simulation;

    public SimulateDefinitionUseCase(
            UnitOfWork unitOfWork,
            DefinitionRepository definitions,
            DefinitionRules rules,
            GraphSimulation simulation) {
        this.unitOfWork = unitOfWork;
        this.definitions = definitions;
        this.rules = rules;
        this.simulation = simulation;
    }

    public Result execute(Command command) {
        return unitOfWork.transactional(() -> {
            Definition definition = definitions
                    .findById(command.definitionId())
                    .orElseThrow(() -> new NotFoundException("No workflow definition exists here."));
            Object draft = definition.draft();
            List<Problem> problems = new ArrayList<>(rules.problems(draft));
            if (draft == null) {
                return new Result(List.of(), List.of(), Map.of(), "no_draft", List.copyOf(problems));
            }
            GraphParser.Result parsed = GraphParser.parse(draft);
            if (parsed.graph() == null) {
                return new Result(List.of(), List.of(), Map.of(), "unreadable_graph", List.copyOf(problems));
            }
            Graph graph = parsed.graph();
            GraphSimulation.Result result = simulation.run(
                    graph,
                    command.input() == null ? Map.of() : command.input(),
                    command.steps() == null ? Map.of() : command.steps());
            return new Result(
                    result.path(), result.decisions(), result.variables(), result.stoppedAt(), List.copyOf(problems));
        });
    }
}
