package dev.ngb.survey.authoring.domain.expression;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.ArrayList;
import java.util.List;

/**
 * Runs one expression tree over a set of answers and computed variables.
 *
 * <p>Evaluation never throws on data. Division by zero, arithmetic with a null operand, a type mismatch, an operator
 * given a list where it wanted a number: each answers null (or false for a comparison) as the contract states, so a
 * public submit can never turn a respondent's input into a failure. A comparison across two types or against null is
 * false on both sides, which is why {@code neq} is not simply the negation of {@code eq}.
 */
public final class Evaluator {

    /** Where an answer and a computed variable come from while one expression runs. */
    public interface Environment {

        /** The submitted value of one question, or of one matrix cell when {@code rowId} is not null. */
        Value answer(String questionId, String rowId);

        /** The value of a computed variable already evaluated, or null when the name is unknown. */
        Value variable(String name);
    }

    private Evaluator() {}

    public static Value evaluate(Expr expression, Environment environment) {
        return switch (expression) {
            case Expr.Literal literal -> literal.value();
            case Expr.Array array -> new Value.Items(
                    array.items().stream().map(item -> evaluate(item, environment)).toList());
            case Expr.Answer answer -> orNil(environment.answer(answer.questionId(), answer.rowId()));
            case Expr.Var variable -> orNil(environment.variable(variable.name()));
            case Expr.Op op -> operation(op, environment);
        };
    }

    /** Whether a value counts as true: only the flag {@code true}. Anything else, null included, is false. */
    public static boolean truthy(Value value) {
        return value instanceof Value.Flag flag && flag.value();
    }

    private static Value orNil(Value value) {
        return value == null ? Value.NIL : value;
    }

    private static Value operation(Expr.Op op, Environment environment) {
        List<Value> args = op.args().stream().map(arg -> evaluate(arg, environment)).toList();
        return switch (op.operator()) {
            case EQ -> new Value.Flag(both(args) && Value.same(args.get(0), args.get(1)));
            case NEQ -> new Value.Flag(both(args) && !Value.same(args.get(0), args.get(1)));
            case GT -> new Value.Flag(compare(args, comparison -> comparison > 0));
            case GTE -> new Value.Flag(compare(args, comparison -> comparison >= 0));
            case LT -> new Value.Flag(compare(args, comparison -> comparison < 0));
            case LTE -> new Value.Flag(compare(args, comparison -> comparison <= 0));
            case IN -> new Value.Flag(member(args.get(0), args.get(1)));
            case CONTAINS -> new Value.Flag(contains(args.get(0), args.get(1)));
            case AND -> new Value.Flag(args.stream().allMatch(Evaluator::truthy));
            case OR -> new Value.Flag(args.stream().anyMatch(Evaluator::truthy));
            case NOT -> new Value.Flag(!truthy(args.get(0)));
            case EXISTS -> new Value.Flag(exists(args.get(0)));
            case EMPTY -> new Value.Flag(!exists(args.get(0)));
            case COUNT -> count(args.get(0));
            case SUM -> sum(args.get(0));
            case ADD -> arithmetic(args.get(0), args.get(1), MathOperation.ADD);
            case SUB -> arithmetic(args.get(0), args.get(1), MathOperation.SUBTRACT);
            case MUL -> arithmetic(args.get(0), args.get(1), MathOperation.MULTIPLY);
            case DIV -> arithmetic(args.get(0), args.get(1), MathOperation.DIVIDE);
            case MIN -> reduce(args, true);
            case MAX -> reduce(args, false);
            case IF -> truthy(args.get(0)) ? args.get(1) : args.get(2);
            case LEN -> length(args.get(0));
            case LOWER -> lower(args.get(0));
        };
    }

    /** Both operands are present, which is what "a comparison with null is false" amounts to. */
    private static boolean both(List<Value> args) {
        return !(args.get(0) instanceof Value.Nil) && !(args.get(1) instanceof Value.Nil);
    }

    private static boolean compare(List<Value> args, java.util.function.IntPredicate test) {
        Value left = args.get(0);
        Value right = args.get(1);
        if (left instanceof Value.Num a && right instanceof Value.Num b) {
            return test.test(a.value().compareTo(b.value()));
        }
        if (left instanceof Value.Text a && right instanceof Value.Text b) {
            return test.test(compareCodePoints(a.value(), b.value()));
        }
        return false;
    }

    /** Text orders by code point, which is the contract's wording and not UTF-16 unit order. */
    private static int compareCodePoints(String left, String right) {
        int leftIndex = 0;
        int rightIndex = 0;
        while (leftIndex < left.length() && rightIndex < right.length()) {
            int a = left.codePointAt(leftIndex);
            int b = right.codePointAt(rightIndex);
            if (a != b) {
                return Integer.compare(a, b);
            }
            leftIndex += Character.charCount(a);
            rightIndex += Character.charCount(b);
        }
        return Integer.compare(left.length() - leftIndex, right.length() - rightIndex);
    }

    /** {@code in}: the left value, or any member of it when it is a list, appears in the right list. */
    private static boolean member(Value needle, Value haystack) {
        if (!(haystack instanceof Value.Items items) || needle instanceof Value.Nil) {
            return false;
        }
        if (needle instanceof Value.Items members) {
            return members.values().stream().anyMatch(value -> containsValue(items, value));
        }
        return containsValue(items, needle);
    }

    private static boolean contains(Value haystack, Value needle) {
        if (haystack instanceof Value.Items items) {
            return containsValue(items, needle);
        }
        if (haystack instanceof Value.Text text && needle instanceof Value.Text search) {
            return text.value().contains(search.value());
        }
        return false;
    }

    private static boolean containsValue(Value.Items items, Value value) {
        return items.values().stream().anyMatch(member -> Value.same(member, value));
    }

    private static boolean exists(Value value) {
        if (value instanceof Value.Nil) {
            return false;
        }
        if (value instanceof Value.Text text) {
            return !text.value().isEmpty();
        }
        if (value instanceof Value.Items items) {
            return !items.values().isEmpty();
        }
        return true;
    }

    private static Value count(Value value) {
        if (value instanceof Value.Items items) {
            return new Value.Num(Value.number(BigDecimal.valueOf(items.values().size())));
        }
        if (value instanceof Value.Nil) {
            return new Value.Num(BigDecimal.ZERO);
        }
        return Value.NIL;
    }

    private static Value sum(Value value) {
        if (!(value instanceof Value.Items items)) {
            return Value.NIL;
        }
        BigDecimal total = BigDecimal.ZERO;
        for (Value member : items.values()) {
            if (!(member instanceof Value.Num number)) {
                return Value.NIL;
            }
            total = total.add(number.value());
        }
        return new Value.Num(Value.number(total));
    }

    private static Value reduce(List<Value> args, boolean minimum) {
        BigDecimal result = null;
        for (Value arg : args) {
            if (!(arg instanceof Value.Num number)) {
                return Value.NIL;
            }
            if (result == null) {
                result = number.value();
            } else {
                result = minimum
                        ? result.min(number.value())
                        : result.max(number.value());
            }
        }
        return result == null ? Value.NIL : new Value.Num(Value.number(result));
    }

    private static Value length(Value value) {
        if (value instanceof Value.Text text) {
            int points = text.value().codePointCount(0, text.value().length());
            return new Value.Num(Value.number(BigDecimal.valueOf(points)));
        }
        if (value instanceof Value.Items items) {
            return new Value.Num(Value.number(BigDecimal.valueOf(items.values().size())));
        }
        return Value.NIL;
    }

    private static Value lower(Value value) {
        return value instanceof Value.Text text
                ? new Value.Text(text.value().toLowerCase(java.util.Locale.ROOT))
                : Value.NIL;
    }

    private enum MathOperation {
        ADD,
        SUBTRACT,
        MULTIPLY,
        DIVIDE
    }

    /** Decimals, rounded to six fraction digits; a null operand, a type mismatch or a zero divisor is null. */
    private static Value arithmetic(Value left, Value right, MathOperation operation) {
        if (!(left instanceof Value.Num a) || !(right instanceof Value.Num b)) {
            return Value.NIL;
        }
        BigDecimal result = switch (operation) {
            case ADD -> a.value().add(b.value());
            case SUBTRACT -> a.value().subtract(b.value());
            case MULTIPLY -> a.value().multiply(b.value());
            case DIVIDE -> {
                if (b.value().signum() == 0) {
                    yield null;
                }
                yield a.value().divide(b.value(), MathContext.DECIMAL128);
            }
        };
        return result == null ? Value.NIL : new Value.Num(Value.number(result));
    }

    /** A list of numbers rendered as text is joined, which is also what a piped list reads as. */
    static List<Value> flatten(Value value) {
        if (value instanceof Value.Items items) {
            return new ArrayList<>(items.values());
        }
        return List.of(value);
    }
}
