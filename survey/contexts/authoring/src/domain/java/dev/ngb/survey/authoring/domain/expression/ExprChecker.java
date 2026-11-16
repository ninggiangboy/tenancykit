package dev.ngb.survey.authoring.domain.expression;

import dev.ngb.survey.authoring.api.document.ValidationCode;
import dev.ngb.survey.authoring.api.document.ValidationError;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Resolves every reference an expression makes against the version that carries it.
 *
 * <p>An {@code answer} must name a question of the version, and its {@code row} must be a row of that question when
 * the question is a matrix. A {@code var} must name a computed variable declared before the expression that reads it,
 * which is what makes a cycle impossible rather than merely unlikely; a variable that names itself is reported as a
 * cycle so a builder sees the one mistake it made.
 */
public final class ExprChecker {

    /**
     * What a reference may resolve to.
     *
     * @param questionTypes every question id and its type
     * @param matrixRows for each matrix question, the row ids it offers
     * @param variableOrder each computed variable and the index it is declared at
     */
    public record Names(
            Map<String, String> questionTypes, Map<String, Set<String>> matrixRows, Map<String, Integer> variableOrder) {

        /** A version with nothing to reference, which is what a preview of an empty draft sees. */
        public static Names empty() {
            return new Names(Map.of(), Map.of(), Map.of());
        }
    }

    private ExprChecker() {}

    /** Every reference this expression cannot resolve, by the pointer of the offending member. */
    public static List<ValidationError> problems(
            Expr expression, String pointer, Names names, String currentVariable) {
        List<ValidationError> errors = new ArrayList<>();
        walk(expression, pointer, names, currentVariable, errors);
        return List.copyOf(errors);
    }

    private static void walk(
            Expr expression,
            String pointer,
            Names names,
            String currentVariable,
            List<ValidationError> errors) {
        switch (expression) {
            case Expr.Literal ignored -> {}
            case Expr.Array array -> {
                for (int index = 0; index < array.items().size(); index++) {
                    walk(array.items().get(index), pointer + "/" + index, names, currentVariable, errors);
                }
            }
            case Expr.Answer answer -> answer(answer, pointer, names, errors);
            case Expr.Var variable -> variable(variable, pointer, names, currentVariable, errors);
            case Expr.Op op -> {
                for (int index = 0; index < op.args().size(); index++) {
                    walk(op.args().get(index), pointer + "/args/" + index, names, currentVariable, errors);
                }
            }
        }
    }

    private static void answer(Expr.Answer answer, String pointer, Names names, List<ValidationError> errors) {
        String type = names.questionTypes().get(answer.questionId());
        if (type == null) {
            errors.add(new ValidationError(
                    pointer + "/answer",
                    ValidationCode.DANGLING_REFERENCE,
                    "no question of this version carries this id"));
            return;
        }
        if (answer.rowId() == null) {
            return;
        }
        Set<String> rows = names.matrixRows().getOrDefault(answer.questionId(), Set.of());
        if (!rows.contains(answer.rowId())) {
            errors.add(new ValidationError(
                    pointer + "/row",
                    ValidationCode.DANGLING_REFERENCE,
                    "a question of type " + type + " offers no such matrix row"));
        }
    }

    private static void variable(
            Expr.Var variable, String pointer, Names names, String currentVariable, List<ValidationError> errors) {
        Integer declaredAt = names.variableOrder().get(variable.name());
        if (variable.name().equals(currentVariable)) {
            errors.add(new ValidationError(
                    pointer + "/var", ValidationCode.CYCLE, "a computed variable cannot read itself"));
            return;
        }
        if (declaredAt == null) {
            errors.add(new ValidationError(
                    pointer + "/var",
                    ValidationCode.DANGLING_REFERENCE,
                    "no computed variable of this version carries this name"));
            return;
        }
        Integer currentAt = currentVariable == null ? null : names.variableOrder().get(currentVariable);
        if (currentAt != null && declaredAt >= currentAt) {
            errors.add(new ValidationError(
                    pointer + "/var",
                    ValidationCode.DANGLING_REFERENCE,
                    "a computed variable may read only a variable declared before it"));
        }
    }
}
