package dev.ngb.survey.authoring.domain.flow;

import dev.ngb.survey.authoring.api.document.ValidationCode;
import dev.ngb.survey.authoring.api.document.ValidationError;
import dev.ngb.survey.authoring.domain.document.Json;
import dev.ngb.survey.authoring.domain.expression.Expr;
import dev.ngb.survey.authoring.domain.expression.ExprChecker;
import dev.ngb.survey.authoring.domain.expression.ExprParser;
import dev.ngb.survey.authoring.domain.expression.Limits;
import dev.ngb.survey.authoring.domain.expression.Value;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * A version document compiled into the shape the evaluator walks: ordered pages with their jumps, questions with
 * their options and columns, computed variables in declared order, scoring rules and the translations.
 *
 * <p>Compiling is the one place a document's expression trees are parsed and their references resolved, so both the
 * draft save and the simulate endpoint answer with the same pointers and codes. A document that fails to compile
 * still produces a plan — every expression missing from it evaluates as null — so a caller always gets a report
 * rather than an exception.
 */
public final class FlowPlan {

    /** One option of a choice, with the points it adds when the question is on the path. */
    public record Option(String id, String value, BigDecimal score) {}

    /** One matrix column, with the points a cell in it adds. */
    public record Column(String id, String value, BigDecimal score) {}

    /**
     * One question: its stable id, its type and the logic attached to it.
     *
     * @param constraints the type-specific limits the type decides, as the document carries them
     */
    public record Question(
            String id,
            String type,
            boolean required,
            boolean identity,
            Expr visibleIf,
            List<Option> options,
            List<Column> columns,
            Set<String> rowIds,
            Map<String, Object> constraints) {}

    /** One jump: a condition and the page it redirects to, or {@code end}. */
    public record Jump(Expr when, String toPage) {}

    /** One page in presentation order. */
    public record Page(String id, Expr skipIf, List<Jump> jumps, List<Question> questions) {}

    /** One computed variable, evaluated after the answers and in this order. */
    public record Computed(String name, Expr expr) {}

    /** One scoring rule: extra points when its condition holds, optionally only while a question is on the path. */
    public record Rule(String questionId, Expr when, BigDecimal points) {}

    private final List<Page> pages;
    private final List<Computed> computed;
    private final List<Rule> rules;
    private final Map<String, Map<String, Object>> translations;
    private final Map<String, Question> questionsById;
    private final Map<String, Integer> pageIndexById;
    private final String defaultLocale;
    private final List<String> locales;
    private final boolean quizEnabled;
    private final List<ValidationError> errors;

    private FlowPlan(
            List<Page> pages,
            List<Computed> computed,
            List<Rule> rules,
            Map<String, Map<String, Object>> translations,
            String defaultLocale,
            List<String> locales,
            boolean quizEnabled,
            List<ValidationError> errors) {
        this.pages = pages;
        this.computed = computed;
        this.rules = rules;
        this.translations = translations;
        this.defaultLocale = defaultLocale;
        this.locales = locales;
        this.quizEnabled = quizEnabled;
        this.errors = errors;

        Map<String, Question> questions = new LinkedHashMap<>();
        Map<String, Integer> pageIndex = new LinkedHashMap<>();
        for (int index = 0; index < pages.size(); index++) {
            Page page = pages.get(index);
            pageIndex.putIfAbsent(page.id(), index);
            for (Question question : page.questions()) {
                questions.putIfAbsent(question.id(), question);
            }
        }
        this.questionsById = Map.copyOf(questions);
        this.pageIndexById = Map.copyOf(pageIndex);
    }

    public List<Page> pages() {
        return pages;
    }

    public List<Computed> computed() {
        return computed;
    }

    public List<Rule> rules() {
        return rules;
    }

    public Map<String, Question> questions() {
        return questionsById;
    }

    public Optional<Question> question(String id) {
        return Optional.ofNullable(questionsById.get(id));
    }

    /** The position of a page in presentation order, or -1 when this version has no such page. */
    public int indexOfPage(String pageId) {
        return pageIndexById.getOrDefault(pageId, -1);
    }

    public Page pageAt(int index) {
        return pages.get(index);
    }

    public String defaultLocale() {
        return defaultLocale;
    }

    public List<String> locales() {
        return locales;
    }

    public boolean quizEnabled() {
        return quizEnabled;
    }

    /** Every reason the document is not evaluable, ordered by pointer; empty means the plan is complete. */
    public List<ValidationError> errors() {
        return errors;
    }

    /** The translation entries of one locale, or an empty map when the version does not carry it. */
    public Map<String, Object> translations(String locale) {
        String chosen = locale == null || locale.isBlank() ? defaultLocale : locale;
        Map<String, Object> entries = translations.get(chosen);
        if (entries == null) {
            entries = translations.get(defaultLocale);
        }
        return entries == null ? Map.of() : entries;
    }

    /** Compiles a document; the result carries every pointer a document fails at and never throws. */
    public static FlowPlan compile(Map<String, Object> document) {
        List<ValidationError> errors = new ArrayList<>();
        List<Parsed> expressions = new ArrayList<>();

        Map<String, Object> settings = Json.object(document.get("settings"));
        Map<String, Object> quiz = settings == null ? null : Json.object(settings.get("quiz"));
        boolean quizEnabled = quiz != null && Boolean.TRUE.equals(quiz.get("enabled"));
        String defaultLocale = settings == null ? null : Json.text(settings.get("default_locale"));
        List<String> locales = texts(settings == null ? null : settings.get("locales"));

        List<Map<String, Object>> rawPages = objects(document.get("pages"));
        Set<String> pageIds = new LinkedHashSet<>();
        for (Map<String, Object> page : rawPages) {
            String id = Json.text(page.get("id"));
            if (id != null) {
                pageIds.add(id);
            }
        }

        List<Page> pages = new ArrayList<>(rawPages.size());
        for (int pageIndex = 0; pageIndex < rawPages.size(); pageIndex++) {
            Map<String, Object> rawPage = rawPages.get(pageIndex);
            String pagePointer = "/pages/" + pageIndex;
            Expr skipIf = expression(rawPage.get("skip_if"), pagePointer + "/skip_if", expressions, errors);

            List<Map<String, Object>> rawJumps = objects(rawPage.get("jumps"));
            List<Jump> jumps = new ArrayList<>(rawJumps.size());
            for (int jumpIndex = 0; jumpIndex < rawJumps.size(); jumpIndex++) {
                Map<String, Object> rawJump = rawJumps.get(jumpIndex);
                Expr when = expression(
                        rawJump.get("when"), pagePointer + "/jumps/" + jumpIndex + "/when", expressions, errors);
                String toPage = Json.text(rawJump.get("to_page"));
                if (toPage != null && !toPage.equals("end") && !pageIds.contains(toPage)) {
                    errors.add(new ValidationError(
                            pagePointer + "/jumps/" + jumpIndex + "/to_page",
                            ValidationCode.DANGLING_REFERENCE,
                            "the jump names no page of this version"));
                }
                jumps.add(new Jump(when, toPage));
            }

            List<Map<String, Object>> rawQuestions = objects(rawPage.get("questions"));
            List<Question> questions = new ArrayList<>(rawQuestions.size());
            for (int questionIndex = 0; questionIndex < rawQuestions.size(); questionIndex++) {
                Map<String, Object> rawQuestion = rawQuestions.get(questionIndex);
                String questionPointer = pagePointer + "/questions/" + questionIndex;
                questions.add(new Question(
                        Json.text(rawQuestion.get("id")),
                        Json.text(rawQuestion.get("type")),
                        Boolean.TRUE.equals(rawQuestion.get("required")),
                        Boolean.TRUE.equals(rawQuestion.get("identity")),
                        expression(rawQuestion.get("visible_if"), questionPointer + "/visible_if", expressions, errors),
                        options(rawQuestion.get("options")),
                        columns(rawQuestion.get("columns")),
                        rowIds(rawQuestion.get("rows")),
                        constraints(rawQuestion.get("constraints"))));
            }
            pages.add(new Page(Json.text(rawPage.get("id")), skipIf, List.copyOf(jumps), List.copyOf(questions)));
        }

        List<Map<String, Object>> rawComputed = objects(document.get("computed"));
        List<Computed> computed = new ArrayList<>(rawComputed.size());
        for (int index = 0; index < rawComputed.size(); index++) {
            Map<String, Object> variable = rawComputed.get(index);
            String name = Json.text(variable.get("name"));
            Expr expr = expression(
                    variable.get("expr"), "/computed/" + index + "/expr", name, expressions, errors);
            computed.add(new Computed(name, expr));
        }

        List<Map<String, Object>> rawRules = objects(Json.object(document.get("scoring")) == null
                ? null
                : Json.object(document.get("scoring")).get("rules"));
        List<Rule> rules = new ArrayList<>(rawRules.size());
        for (int index = 0; index < rawRules.size(); index++) {
            Map<String, Object> rule = rawRules.get(index);
            rules.add(new Rule(
                    Json.text(rule.get("question")),
                    expression(rule.get("when"), "/scoring/rules/" + index + "/when", expressions, errors),
                    decimal(rule.get("points"))));
        }

        Map<String, Map<String, Object>> translations = translations(document.get("translations"), expressions, errors);

        ExprChecker.Names names = names(pages, computed);
        for (Parsed parsed : expressions) {
            if (parsed.expression() == null) {
                continue;
            }
            errors.addAll(ExprChecker.problems(parsed.expression(), parsed.pointer(), names, parsed.variable()));
        }

        long totalNodes = expressions.stream()
                .filter(parsed -> parsed.expression() != null)
                .mapToLong(parsed -> parsed.expression().nodes())
                .sum();
        if (totalNodes > Limits.VERSION_NODES) {
            errors.add(new ValidationError(
                    "",
                    ValidationCode.LIMIT_EXCEEDED,
                    "a version may hold at most " + Limits.VERSION_NODES + " expression nodes"));
        }

        return new FlowPlan(
                List.copyOf(pages),
                List.copyOf(computed),
                List.copyOf(rules),
                translations,
                defaultLocale,
                locales,
                quizEnabled,
                sorted(errors));
    }

    /** One expression and where it sits, so a report lands on the offending member. */
    private record Parsed(String pointer, String variable, Expr expression) {}

    private static Expr expression(
            Object document, String pointer, List<Parsed> expressions, List<ValidationError> errors) {
        return expression(document, pointer, null, expressions, errors);
    }

    private static Expr expression(
            Object document,
            String pointer,
            String variable,
            List<Parsed> expressions,
            List<ValidationError> errors) {
        if (document == null) {
            return null;
        }
        ExprParser.Result parsed = ExprParser.parse(document, pointer);
        errors.addAll(parsed.problems());
        expressions.add(new Parsed(pointer, variable, parsed.expression()));
        return parsed.expression();
    }

    private static ExprChecker.Names names(List<Page> pages, List<Computed> computed) {
        Map<String, String> types = new LinkedHashMap<>();
        Map<String, Set<String>> matrixRows = new LinkedHashMap<>();
        for (Page page : pages) {
            for (Question question : page.questions()) {
                if (question.id() == null) {
                    continue;
                }
                types.putIfAbsent(question.id(), question.type());
                if (question.type() != null && question.type().equals("matrix")) {
                    matrixRows.putIfAbsent(question.id(), question.rowIds());
                }
            }
        }
        Map<String, Integer> order = new LinkedHashMap<>();
        for (int index = 0; index < computed.size(); index++) {
            if (computed.get(index).name() != null) {
                order.putIfAbsent(computed.get(index).name(), index);
            }
        }
        return new ExprChecker.Names(types, matrixRows, order);
    }

    private static Map<String, Map<String, Object>> translations(
            Object raw, List<Parsed> expressions, List<ValidationError> errors) {
        Map<String, Map<String, Object>> translations = new LinkedHashMap<>();
        Map<String, Object> byLocale = Json.object(raw);
        if (byLocale == null) {
            return Map.of();
        }
        byLocale.forEach((locale, entries) -> {
            Map<String, Object> texts = Json.object(entries);
            if (texts == null) {
                return;
            }
            translations.put(locale, texts);
            texts.forEach((key, value) -> pipedTokens(
                    value, "/translations/" + escape(locale) + "/" + escape(key), expressions, errors));
        });
        return translations;
    }

    /** A piped sentence carries expressions inside its {@code {"ref": ...}} tokens. */
    private static void pipedTokens(
            Object value, String pointer, List<Parsed> expressions, List<ValidationError> errors) {
        List<Object> tokens = Json.array(value);
        if (tokens == null) {
            return;
        }
        for (int index = 0; index < tokens.size(); index++) {
            Map<String, Object> token = Json.object(tokens.get(index));
            if (token != null && token.containsKey("ref")) {
                expression(token.get("ref"), pointer + "/" + index + "/ref", expressions, errors);
            }
        }
    }

    private static List<Option> options(Object raw) {
        List<Map<String, Object>> nodes = objects(raw);
        List<Option> options = new ArrayList<>(nodes.size());
        for (Map<String, Object> node : nodes) {
            options.add(new Option(Json.text(node.get("id")), Json.text(node.get("value")), decimal(node.get("score"))));
        }
        return List.copyOf(options);
    }

    private static List<Column> columns(Object raw) {
        List<Map<String, Object>> nodes = objects(raw);
        List<Column> columns = new ArrayList<>(nodes.size());
        for (Map<String, Object> node : nodes) {
            columns.add(new Column(
                    Json.text(node.get("id")), Json.text(node.get("value")), decimal(node.get("score"))));
        }
        return List.copyOf(columns);
    }

    private static Set<String> rowIds(Object raw) {
        Set<String> ids = new LinkedHashSet<>();
        for (Map<String, Object> node : objects(raw)) {
            String id = Json.text(node.get("id"));
            if (id != null) {
                ids.add(id);
            }
        }
        return Set.copyOf(ids);
    }

    private static Map<String, Object> constraints(Object raw) {
        Map<String, Object> constraints = Json.object(raw);
        return constraints == null ? Map.of() : constraints;
    }

    private static BigDecimal decimal(Object value) {
        if (value instanceof BigDecimal decimal) {
            return Value.number(decimal);
        }
        if (value instanceof Number number) {
            return Value.number(new BigDecimal(number.toString()));
        }
        return BigDecimal.ZERO;
    }

    private static List<Map<String, Object>> objects(Object value) {
        List<Object> array = Json.array(value);
        if (array == null) {
            return List.of();
        }
        List<Map<String, Object>> objects = new ArrayList<>(array.size());
        for (Object member : array) {
            Map<String, Object> object = Json.object(member);
            if (object != null) {
                objects.add(object);
            }
        }
        return objects;
    }

    private static List<String> texts(Object value) {
        List<Object> array = Json.array(value);
        if (array == null) {
            return List.of();
        }
        List<String> texts = new ArrayList<>(array.size());
        for (Object member : array) {
            String text = Json.text(member);
            if (text != null) {
                texts.add(text);
            }
        }
        return List.copyOf(texts);
    }

    private static String escape(String segment) {
        return segment.replace("~", "~0").replace("/", "~1");
    }

    private static List<ValidationError> sorted(List<ValidationError> errors) {
        return errors.stream()
                .sorted(Comparator.comparing(ValidationError::pointer)
                        .thenComparing(error -> error.code().wireName())
                        .thenComparing(ValidationError::message))
                .toList();
    }
}
