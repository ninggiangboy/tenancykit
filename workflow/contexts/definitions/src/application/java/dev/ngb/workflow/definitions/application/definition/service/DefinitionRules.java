package dev.ngb.workflow.definitions.application.definition.service;

import dev.ngb.core.error.BadRequestException;
import dev.ngb.workflow.definitions.domain.problem.Problem;
import dev.ngb.workflow.definitions.domain.problem.ProblemCodes;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/** The rules a definition's own fields follow, and the one place a draft is validated. */
public class DefinitionRules {

    private static final Pattern CODE = Pattern.compile("^[a-z][a-z0-9_-]{1,63}$");

    private static final int MAX_NAME = 200;

    /** The graph a definition is published from; an undefined draft is one problem, not a broken document. */
    public List<Problem> problems(Object draft) {
        if (draft == null) {
            return List.of(new Problem(
                    "/", ProblemCodes.GRAPH_INVALID, "a definition publishes the graph it holds"));
        }
        dev.ngb.workflow.definitions.domain.definition.GraphParser.Result parsed =
                dev.ngb.workflow.definitions.domain.definition.GraphParser.parse(draft);
        List<Problem> problems = new ArrayList<>(parsed.problems());
        if (parsed.graph() != null) {
            problems.addAll(dev.ngb.workflow.definitions.domain.definition.StructureChecker.problems(parsed.graph()));
        }
        return List.copyOf(problems);
    }

    public String code(String raw) {
        String code = raw == null ? "" : raw.strip();
        if (!CODE.matcher(code).matches()) {
            throw new BadRequestException(
                    "a definition code starts with a letter and holds lower-case letters, digits, '_' or '-' (2 to 64)");
        }
        return code;
    }

    public String name(String raw) {
        String name = raw == null ? "" : raw.strip();
        if (name.isEmpty() || name.length() > MAX_NAME) {
            throw new BadRequestException("a definition name is 1 to " + MAX_NAME + " characters");
        }
        return name;
    }
}
