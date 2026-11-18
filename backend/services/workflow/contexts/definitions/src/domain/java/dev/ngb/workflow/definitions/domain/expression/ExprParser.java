package dev.ngb.workflow.definitions.domain.expression;

import dev.ngb.workflow.definitions.domain.problem.Problem;
import dev.ngb.workflow.definitions.domain.problem.ProblemCodes;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Turns a JSON expression into a typed tree, or reports why it cannot.
 *
 * <p>This is the gate the contract claims: an unknown operator, an arity the operator does not accept, a malformed
 * path, a tree deeper than {@link Limits#DEPTH}, more than {@link Limits#NODES} nodes or an array literal over
 * {@link Limits#ARRAY_ITEMS} items never reaches the evaluator. The schema carries the shape; the budgets are
 * checked here because a schema cannot count.
 */
public final class ExprParser {

    /** The tree, and every reason the document is not one. An unparsable document has a null tree. */
    public record Result(Expr expression, List<Problem> problems) {}

    private ExprParser() {}

    /**
     * @param document the expression as the definition document carries it
     * @param pointer the RFC 6901 pointer of the expression, so a report names the member
     */
    public static Result parse(Object document, String pointer) {
        List<Problem> problems = new ArrayList<>();
        Expr expression = build(document, pointer, problems);
        if (expression != null) {
            if (expression.depth() > Limits.DEPTH) {
                problems.add(new Problem(
                        pointer,
                        ProblemCodes.LIMIT_EXCEEDED,
                        "an expression may nest at most " + Limits.DEPTH + " operators"));
            }
            if (expression.nodes() > Limits.NODES) {
                problems.add(new Problem(
                        pointer,
                        ProblemCodes.LIMIT_EXCEEDED,
                        "an expression may hold at most " + Limits.NODES + " nodes"));
            }
        }
        return new Result(expression, List.copyOf(problems));
    }

    private static Expr build(Object document, String pointer, List<Problem> problems) {
        List<Object> items = Json.array(document);
        if (items != null) {
            if (items.size() > Limits.ARRAY_ITEMS) {
                problems.add(new Problem(
                        pointer,
                        ProblemCodes.LIMIT_EXCEEDED,
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
        if (members.containsKey("input")) {
            return pathReference(members, "input", pointer, problems, Expr.Input::new);
        }
        if (members.containsKey("step")) {
            return step(members, pointer, problems);
        }
        if (members.containsKey("var")) {
            String name = Json.text(members.get("var"));
            if (name == null || name.isBlank()) {
                problems.add(new Problem(
                        pointer + "/var",
                        ProblemCodes.EXPRESSION_INVALID,
                        "a variable reference names the variable it reads"));
                return null;
            }
            return new Expr.Var(name);
        }
        if (members.containsKey("item")) {
            return pathReference(members, "item", pointer, problems, Expr.Item::new);
        }
        if (members.containsKey("index")) {
            if (!Json.isTrue(members.get("index"))) {
                problems.add(new Problem(
                        pointer + "/index",
                        ProblemCodes.EXPRESSION_INVALID,
                        "an index reference is written {\"index\": true}"));
                return null;
            }
            return new Expr.Index();
        }

        problems.add(new Problem(
                pointer,
                ProblemCodes.EXPRESSION_INVALID,
                "an expression is a literal, a list, or a reference to input, a step, a variable, an item or an index, "
                        + "or an operator"));
        return null;
    }

    /** A reference whose whole meaning is one path: input and the current for-each item. */
    private static Expr pathReference(
            Map<String, Object> members,
            String member,
            String pointer,
            List<Problem> problems,
            java.util.function.Function<String, Expr> factory) {
        String path = path(members.get(member));
        if (!Json.validPath(path)) {
            problems.add(new Problem(
                    pointer + "/" + member,
                    path == null ? ProblemCodes.EXPRESSION_INVALID : ProblemCodes.INVALID_PATH,
                    path == null
                            ? "a " + member + " reference needs a path; use an empty string for the whole value"
                            : "a path is a dotted list of names, for example customer.id or items.0.price"));
            return null;
        }
        return factory.apply(path);
    }

    private static Expr step(Map<String, Object> members, String pointer, List<Problem> problems) {
        String nodeId = Json.text(members.get("step"));
        if (nodeId == null || nodeId.isBlank()) {
            problems.add(new Problem(
                    pointer + "/step",
                    ProblemCodes.EXPRESSION_INVALID,
                    "a step reference names the node whose output it reads"));
            return null;
        }
        String path = path(members.get("path"));
        if (!Json.validPath(path)) {
            problems.add(new Problem(
                    pointer + "/path",
                    ProblemCodes.INVALID_PATH,
                    "a path is a dotted list of names, for example customer.id or items.0.price"));
            return null;
        }
        return new Expr.Step(nodeId, path);
    }

    /** A path member is text, and an absent one names the whole value. */
    private static String path(Object value) {
        if (value == null) {
            return "";
        }
        return Json.text(value);
    }

    private static Expr operation(
            String name, Map<String, Object> members, String pointer, List<Problem> problems) {
        Operator operator = Operator.named(name);
        if (operator == null) {
            problems.add(new Problem(
                    pointer + "/op",
                    ProblemCodes.UNKNOWN_OPERATOR,
                    "the language defines no operator by this name"));
            return null;
        }
        List<Object> args = Json.array(members.get("args"));
        if (args == null) {
            problems.add(new Problem(
                    pointer + "/args",
                    ProblemCodes.ARITY_MISMATCH,
                    "an operator needs an array of arguments"));
            return null;
        }
        if (!operator.accepts(args.size())) {
            problems.add(new Problem(
                    pointer + "/args",
                    ProblemCodes.ARITY_MISMATCH,
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
