package dev.ngb.survey.collection.application.response.exception;

import dev.ngb.survey.authoring.api.flow.SubmitProblem;
import java.util.List;

/**
 * A submission does not satisfy the version: an answer off the path, a required question unanswered, a value that
 * breaks its type or constraints.
 *
 * <p>The problems ride along by field and code, never by value, because the refusal goes back to a browser and the
 * value it sent is the one thing it already knows.
 */
public class SubmitInvalidException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final transient List<SubmitProblem> problems;

    public SubmitInvalidException(List<SubmitProblem> problems) {
        super("the submission does not satisfy the version");
        this.problems = List.copyOf(problems);
    }

    public List<SubmitProblem> problems() {
        return problems;
    }
}
