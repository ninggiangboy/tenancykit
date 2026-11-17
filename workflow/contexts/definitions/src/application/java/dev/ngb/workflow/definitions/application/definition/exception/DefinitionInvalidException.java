package dev.ngb.workflow.definitions.application.definition.exception;

import dev.ngb.workflow.definitions.domain.problem.Problem;
import java.util.List;

/**
 * A draft that cannot be published: every structural reason, by pointer and code, so the author can fix them in one
 * round. The HTTP edge answers 422; nothing was written.
 */
public class DefinitionInvalidException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final transient List<Problem> problems;

    public DefinitionInvalidException(List<Problem> problems) {
        super("the workflow cannot be published: it has " + problems.size() + " problem(s)");
        this.problems = List.copyOf(problems);
    }

    public List<Problem> problems() {
        return problems;
    }
}
