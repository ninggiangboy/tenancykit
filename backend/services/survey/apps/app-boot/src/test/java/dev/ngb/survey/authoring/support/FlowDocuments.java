package dev.ngb.survey.authoring.support;

import dev.ngb.survey.authoring.domain.expression.Limits;
import dev.ngb.survey.authoring.domain.flow.Answers;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds the version documents and answer sets the engine's own tests need, with fixed ids so a failure names the
 * same pointer on every run.
 *
 * <p>These are only the shapes a vector cannot express — a document built to sit exactly on a budget — never an
 * operator case: an operator's behaviour belongs in {@code contracts/survey/expression-vectors}.
 */
public final class FlowDocuments {

    public static final String PAGE_ID = "11111111-1111-7111-8111-111111111111";
    public static final String QUESTION_ID = "00210257-69ad-8cf6-dce4-12aac7c47365";

    private FlowDocuments() {}

    /** A complete version whose only computed variable is {@code r}, carrying this expression. */
    public static Map<String, Object> versionWith(Object expression) {
        return versionWithComputed(List.of(computed("r", expression)));
    }

    /** A complete version whose computed list is exactly these variables. */
    public static Map<String, Object> versionWithComputed(List<Map<String, Object>> computed) {
        Map<String, Object> settings = new LinkedHashMap<>();
        settings.put("default_locale", "en");
        settings.put("locales", List.of("en"));
        settings.put("quiz", Map.of("enabled", false, "return_score", false));

        Map<String, Object> question = new LinkedHashMap<>();
        question.put("id", QUESTION_ID);
        question.put("type", "number");

        Map<String, Object> page = new LinkedHashMap<>();
        page.put("id", PAGE_ID);
        page.put("questions", List.of(question));

        Map<String, Object> document = new LinkedHashMap<>();
        document.put("settings", settings);
        document.put("pages", List.of(page));
        document.put("computed", computed);
        document.put("scoring", Map.of("rules", List.of()));
        document.put("translations", Map.of("en", Map.of("survey.title", "T")));
        return document;
    }

    /** The same version with one expression repeated {@code times} times as its computed list. */
    public static Map<String, Object> versionWithRepeated(Object expression, int times) {
        List<Map<String, Object>> computed = new ArrayList<>(times);
        for (int index = 0; index < times; index++) {
            computed.add(computed("v" + index, expression));
        }
        return versionWithComputed(computed);
    }

    /**
     * An expression of exactly {@code nodes} nodes. Up to eight children the tree is one {@code and} over its parts;
     * above that it spreads across eight children, so it stays far inside the depth budget while holding an exact
     * count. The contract's node count is what the version total sums, so a test that sits exactly on the limit needs
     * that exact number rather than an approximation.
     */
    public static Map<String, Object> expressionOfNodes(int nodes) {
        if (nodes < 3 || nodes > Limits.NODES) {
            throw new IllegalArgumentException("an operator expression holds 3 to " + Limits.NODES + " nodes");
        }
        return node(nodes);
    }

    private static Map<String, Object> node(int nodes) {
        if (nodes == 1) {
            return Map.of("answer", QUESTION_ID);
        }
        int children = Math.min(8, nodes - 1);
        int remaining = nodes - 1;
        List<Object> args = new ArrayList<>(children);
        for (int index = 0; index < children; index++) {
            int share = remaining / (children - index);
            args.add(node(share));
            remaining -= share;
        }
        return Map.of("op", "and", "args", List.copyOf(args));
    }

    /** An {@code and} nested {@code depth} times around one answer, which is what the depth budget measures. */
    public static Object nestedNot(int depth) {
        Object expression = Map.of("answer", QUESTION_ID);
        for (int level = 0; level < depth; level++) {
            expression = Map.of("op", "not", "args", List.of(expression));
        }
        return expression;
    }

    /** Answers for every question of a document, so an evaluation is not short-circuited by an absent answer. */
    public static Answers answersFor(Map<String, Object> document, boolean answered) {
        Map<String, Object> answers = new LinkedHashMap<>();
        if (answered) {
            answers.put(QUESTION_ID, 1);
        }
        return Answers.of(answers);
    }

    private static Map<String, Object> computed(String name, Object expression) {
        Map<String, Object> variable = new LinkedHashMap<>();
        variable.put("name", name);
        variable.put("expr", expression);
        return variable;
    }
}
