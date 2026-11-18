package dev.ngb.survey.authoring.api.flow;

/**
 * One reason a submission is refused, by the field it concerns.
 *
 * <p>A submit is a public call: the answer names the field and a code, never the value a respondent sent, because the
 * refusal is echoed to a browser that may be a bot and its own answer is the one thing it already knows.
 */
public record SubmitProblem(String field, String code) {

    /** An answer names a question that is not on the path the respondent actually took. */
    public static final String ANSWER_NOT_ON_PATH = "answer_not_on_path";
    /** A required question on the path has no answer. */
    public static final String REQUIRED = "required";
    /** An answer does not have the question's type: an address that is not one, a choice id not in the question. */
    public static final String INVALID_ANSWER = "invalid_answer";
    /** A constraint the question states is broken: a length, a range, a selection count, a missing matrix row. */
    public static final String CONSTRAINT = "constraint";
    /** A file reference is not an upload of this visit, or its object does not match what was declared. */
    public static final String INVALID_UPLOAD = "invalid_upload";

    public SubmitProblem {
        java.util.Objects.requireNonNull(field, "field");
        java.util.Objects.requireNonNull(code, "code");
    }
}
