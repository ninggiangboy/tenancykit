package dev.ngb.survey.authoring.domain.expression;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/**
 * The five values an expression can produce: text, a decimal, a flag, a list and null.
 *
 * <p>A number is a decimal rounded to six fraction digits, exactly as the contract states, so a value read from a
 * document and a value computed by an operator come out in the same shape. Two numbers are equal when
 * {@code compareTo} says so, never {@code equals}, because {@code 1.0} and {@code 1} are the same value written two
 * ways.
 */
public sealed interface Value {

    /** The absent value: an unanswered question, a missing cell, a type mismatch. */
    record Nil() implements Value {}

    /** A boolean. */
    record Flag(boolean value) implements Value {}

    /** A decimal, already rounded and stripped. */
    record Num(BigDecimal value) implements Value {}

    /** Text. */
    record Text(String value) implements Value {}

    /** A list: a multi-choice answer, a file answer, a literal array. */
    record Items(List<Value> values) implements Value {}

    Nil NIL = new Nil();

    private static BigDecimal decimal(BigDecimal raw) {
        BigDecimal rounded = raw.setScale(6, RoundingMode.HALF_EVEN);
        return rounded.signum() == 0 ? BigDecimal.ZERO : rounded.stripTrailingZeros();
    }

    /** The same decimal rounded the way every arithmetic result is. */
    static BigDecimal number(BigDecimal raw) {
        return decimal(raw);
    }

    /** The value of a document member, as the JSON it is: a scalar, a list or null. */
    static Value of(Object raw) {
        if (raw == null) {
            return NIL;
        }
        if (raw instanceof Value value) {
            return value;
        }
        if (raw instanceof Boolean flag) {
            return new Flag(flag);
        }
        if (raw instanceof String text) {
            return new Text(text);
        }
        if (raw instanceof BigDecimal || raw instanceof Double || raw instanceof Float
                || raw instanceof Integer || raw instanceof Long || raw instanceof Short || raw instanceof Byte
                || raw instanceof java.math.BigInteger) {
            return new Num(decimal(new BigDecimal(raw.toString())));
        }
        if (raw instanceof List<?> members) {
            List<Value> values = new ArrayList<>(members.size());
            members.forEach(member -> values.add(of(member)));
            return new Items(List.copyOf(values));
        }
        return NIL;
    }

    /** The value as the JSON a response carries: a scalar, a list of scalars or null. */
    static Object json(Value value) {
        if (value instanceof Nil) {
            return null;
        }
        if (value instanceof Flag flag) {
            return flag.value();
        }
        if (value instanceof Num number) {
            return number.value();
        }
        if (value instanceof Text text) {
            return text.value();
        }
        List<Object> members = new ArrayList<>();
        for (Value member : ((Items) value).values()) {
            members.add(json(member));
        }
        return members;
    }

    /** Semantic equality: numbers by value, text by content, lists member by member, null only with null. */
    static boolean same(Value left, Value right) {
        if (left instanceof Nil || right instanceof Nil) {
            return left instanceof Nil && right instanceof Nil;
        }
        if (left instanceof Num a && right instanceof Num b) {
            return a.value().compareTo(b.value()) == 0;
        }
        if (left instanceof Text a && right instanceof Text b) {
            return a.value().equals(b.value());
        }
        if (left instanceof Flag a && right instanceof Flag b) {
            return a.value() == b.value();
        }
        if (left instanceof Items a && right instanceof Items b) {
            if (a.values().size() != b.values().size()) {
                return false;
            }
            for (int index = 0; index < a.values().size(); index++) {
                if (!same(a.values().get(index), b.values().get(index))) {
                    return false;
                }
            }
            return true;
        }
        return false;
    }

    /** The text a piped token renders, which is how a respondent reads the value. */
    static String text(Value value) {
        if (value instanceof Nil) {
            return "";
        }
        if (value instanceof Flag flag) {
            return Boolean.toString(flag.value());
        }
        if (value instanceof Num number) {
            return number.value().toPlainString();
        }
        if (value instanceof Text text) {
            return text.value();
        }
        List<String> members = new ArrayList<>();
        for (Value member : ((Items) value).values()) {
            members.add(text(member));
        }
        return String.join(", ", members);
    }
}
