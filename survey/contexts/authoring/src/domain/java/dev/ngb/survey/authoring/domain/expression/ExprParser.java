package dev.ngb.survey.authoring.domain.expression;

import dev.ngb.survey.authoring.api.document.ValidationCode;
import dev.ngb.survey.authoring.api.document.ValidationError;
import dev.ngb.survey.authoring.domain.document.Json;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Turns a JSON expression into a typed tree, or reports why it cannot.
 *
 * <p>This is the gate the contract claims: an unknown operator, an arity the operator does not accept, a tree deeper
 * than {@link Limits#DEPTH}, more than {@link Limits#NODES} nodes or an array literal over {@link Limits#ARRAY_ITEMS}
 * items never reaches the evaluator. The schema carries the shape; the budgets are checked here because a schema
 * cannot count.
 */
public final class ExprParser {

    /** The tree, and every reason the document is not one. An unparsable document has a null tree. */
    public record Result(Expr expression, List<ValidationError> problems) {}

    private ExprParser() {}

    /**
     * @param document the expression as the version document carries it
     * @param pointer the RFC 6901 pointer of the expression, so a report names the member
     */
    public static Result parse(Object document, String pointer) {
        List<ValidationError> problems = new ArrayList<>();
        Expr expression = build(document, pointer, problems);
        if (expression != null) {
            if (expression.depth() > Limits.DEPTH) {
                problems.add(new ValidationError(
                        pointer,
                        ValidationCode.LIMIT_EXCEEDED,
                        "an expression may nest at most " + Limits.DEPTH + " operators"));
            }
            if (expression.nodes() > Limits.NODES) {
                problems.add(new ValidationError(
                        pointer,
                        ValidationCode.LIMIT_EXCEEDED,
                        "an expression may hold at most " + Limits.NODES + " nodes"));
            }
        }
        return new Result(expression, List.copyOf(problems));
    }

    private static Expr build(Object document, String pointer, List<ValidationError> problems) {
        List<Object> items = Json.array(document);
        if (items != null) {
            if (items.size() > Limits.ARRAY_ITEMS) {
                problems.add(new ValidationError(
                        pointer,
                        ValidationCode.LIMIT_EXCEEDED,
                        "an array literal may hold at most " + Limits.ARRAY_ITEMS + " items"));
            }
            List<Expr> values = new ArrayList<>(items.size());
            for (int index = 0; index < items.size(); index++) {
                values.add(orNil(build(items.get(index), pointer + "/" + index, problems)));
            }
            return new Expr.Array(List.copyOf(values));
        }

        Map<String, Object> members = Json.object(document);
        if (members == null) {
            return new Expr.Literal(Value.of(document));
        }

        String operatorName = Json.text(members.get("op"));
        if (operatorName != null) {
            return operation(operatorName, members, pointer, problems);
        }
        String questionId = Json.text(members.get("answer"));
        if (questionId != null) {
            return new Expr.Answer(questionId, Json.text(members.get("row")));
        }
        String variableName = Json.text(members.get("var"));
        if (variableName != null) {
            return new Expr.Var(variableName);
        }

        problems.add(new ValidationError(
                pointer,
                ValidationCode.UNKNOWN_TYPE,
                "an expression is a literal, a list, an answer reference, a variable reference or an operator"));
        return null;
    }

    private static Expr operation(
            String name, Map<String, Object> members, String pointer, List<ValidationError> problems) {
        Operator operator = Operator.named(name);
        if (operator == null) {
            problems.add(new ValidationError(
                    pointer + "/op",
                    ValidationCode.EXPRESSION_INVALID,
                    "the language defines no operator by this name"));
            return null;
        }
        List<Object> args = Json.array(members.get("args"));
        if (args == null) {
            problems.add(new ValidationError(
                    pointer + "/args",
                    ValidationCode.EXPRESSION_INVALID,
                    "an operator needs an array of arguments"));
            return null;
        }
        if (!operator.accepts(args.size())) {
            problems.add(new ValidationError(
                    pointer + "/args",
                    ValidationCode.EXPRESSION_INVALID,
                    "this operator takes " + operator.arityDescription() + " arguments, not " + args.size()));
        }
        List<Expr> arguments = new ArrayList<>(args.size());
        for (int index = 0; index < args.size(); index++) {
            arguments.add(orNil(build(args.get(index), pointer + "/args/" + index, problems)));
        }
        return new Expr.Op(operator, List.copyOf(arguments));
    }

    /** A member that failed to parse still occupies its place, so a report lists every problem in one round. */
    private static Expr orNil(Expr expression) {
        return expression == null ? new Expr.Literal(Value.NIL) : expression;
    }
}
