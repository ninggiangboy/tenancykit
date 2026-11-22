package dev.ngb.survey.authoring.domain.flow;

import dev.ngb.survey.authoring.api.flow.SubmitProblem;
import dev.ngb.survey.authoring.domain.document.Json;
import dev.ngb.survey.authoring.domain.expression.Value;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Decides whether a set of answers may become a completed response, against the version the visit pinned.
 *
 * <p>Three questions, in order: is every answer on the path the respondent actually took, is every required question
 * on that path answered, and does each answer satisfy its question's type and constraints. A client's own
 * {@code vars} or {@code score} is never read, because the server recomputes both and trusts neither.
 *
 * <p>Only the field and a code leave this class. The answer itself is never echoed: a refusal is returned to a browser
 * that may be a bot, and a message that quoted the value would help it learn which guess was close.
 */
public final class SubmitRules {

    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");
    private static final Pattern DATE = Pattern.compile("^\\d{4}-\\d{2}-\\d{2}$");

    private SubmitRules() {}

    /**
     * @param plan the version the visit pinned
     * @param answers what the respondent submitted
     * @param visible which questions the path makes visible, from the same evaluator a preview runs
     * @param knownUploads the upload ids that belong to this visit, which a file answer may reference
     * @return every reason a submission is refused, in question order; empty means it may complete
     */
    public static List<SubmitProblem> problems(
            FlowPlan plan, Answers answers, Map<String, Boolean> visible, Set<String> knownUploads) {
        List<SubmitProblem> problems = new ArrayList<>();

        // An answer off the path is a client that disagreed with the server about a branch: refuse rather than store it.
        for (Map.Entry<String, Object> answer : answers.submitted().entrySet()) {
            String questionId = answer.getKey();
            if (!plan.questions().containsKey(questionId)) {
                problems.add(new SubmitProblem(questionId, SubmitProblem.ANSWER_NOT_ON_PATH));
                continue;
            }
            if (!Boolean.TRUE.equals(visible.get(questionId)) && !empty(answer.getValue())) {
                problems.add(new SubmitProblem(questionId, SubmitProblem.ANSWER_NOT_ON_PATH));
            }
        }

        for (FlowPlan.Page page : plan.pages()) {
            for (FlowPlan.Question question : page.questions()) {
                if (question.id() == null || !Boolean.TRUE.equals(visible.get(question.id()))) {
                    continue;
                }
                Value answer = answers.value(question.id(), null);
                if (answer instanceof Value.Nil) {
                    if (question.required()) {
                        problems.add(new SubmitProblem(question.id(), SubmitProblem.REQUIRED));
                    }
                    continue;
                }
                if (empty(Value.json(answer))) {
                    if (question.required()) {
                        problems.add(new SubmitProblem(question.id(), SubmitProblem.REQUIRED));
                    }
                    continue;
                }
                problems.addAll(typed(question, answer, knownUploads));
            }
        }
        return List.copyOf(problems);
    }

    /** Whether a submitted member counts as no answer: absent, null, an empty string or an empty list. */
    private static boolean empty(Object raw) {
        if (raw == null) {
            return true;
        }
        if (raw instanceof String text) {
            return text.isBlank();
        }
        if (raw instanceof List<?> members) {
            return members.isEmpty();
        }
        if (raw instanceof Map<?, ?> members) {
            return members.isEmpty();
        }
        return false;
    }

    /** The type-specific check for one answered question. */
    private static List<SubmitProblem> typed(
            FlowPlan.Question question, Value answer, Set<String> knownUploads) {
        Map<String, Object> constraints = question.constraints();
        return switch (question.type() == null ? "" : question.type()) {
            case "short_text", "long_text" -> text(question.id(), answer, constraints);
            case "email" -> email(question.id(), answer, constraints);
            case "number" -> number(question.id(), answer, constraints);
            case "date" -> date(question.id(), answer, constraints);
            case "rating" -> rating(question.id(), answer, constraints);
            case "nps" -> nps(question.id(), answer);
            case "single_choice" -> singleChoice(question.id(), answer, question);
            case "multi_choice" -> multiChoice(question.id(), answer, question);
            case "matrix" -> matrix(question, answer);
            case "file" -> file(question.id(), answer, constraints, knownUploads);
            default -> List.of(new SubmitProblem(question.id(), SubmitProblem.INVALID_ANSWER));
        };
    }

    private static List<SubmitProblem> text(String id, Value answer, Map<String, Object> constraints) {
        if (!(answer instanceof Value.Text text)) {
            return problem(id, SubmitProblem.INVALID_ANSWER);
        }
        int length = text.value().codePointCount(0, text.value().length());
        Long min = integer(constraints.get("min_length"));
        Long max = integer(constraints.get("max_length"));
        if (min != null && length < min) {
            return problem(id, SubmitProblem.CONSTRAINT);
        }
        if (max != null && length > max) {
            return problem(id, SubmitProblem.CONSTRAINT);
        }
        return List.of();
    }

    private static List<SubmitProblem> email(String id, Value answer, Map<String, Object> constraints) {
        if (!(answer instanceof Value.Text text) || !EMAIL.matcher(text.value()).matches()) {
            return problem(id, SubmitProblem.INVALID_ANSWER);
        }
        Long max = integer(constraints.get("max_length"));
        return max != null && text.value().length() > max ? problem(id, SubmitProblem.CONSTRAINT) : List.of();
    }

    private static List<SubmitProblem> number(String id, Value answer, Map<String, Object> constraints) {
        if (!(answer instanceof Value.Num number)) {
            return problem(id, SubmitProblem.INVALID_ANSWER);
        }
        BigDecimal value = number.value();
        if (Boolean.TRUE.equals(constraints.get("integer_only")) && value.stripTrailingZeros().scale() > 0) {
            return problem(id, SubmitProblem.CONSTRAINT);
        }
        Long decimals = integer(constraints.get("decimals"));
        if (decimals != null && Math.max(0, value.stripTrailingZeros().scale()) > decimals) {
            return problem(id, SubmitProblem.CONSTRAINT);
        }
        BigDecimal min = decimal(constraints.get("min"));
        BigDecimal max = decimal(constraints.get("max"));
        if (min != null && value.compareTo(min) < 0) {
            return problem(id, SubmitProblem.CONSTRAINT);
        }
        if (max != null && value.compareTo(max) > 0) {
            return problem(id, SubmitProblem.CONSTRAINT);
        }
        return List.of();
    }

    private static List<SubmitProblem> date(String id, Value answer, Map<String, Object> constraints) {
        if (!(answer instanceof Value.Text text) || !DATE.matcher(text.value()).matches()) {
            return problem(id, SubmitProblem.INVALID_ANSWER);
        }
        String min = Json.text(constraints.get("min_date"));
        String max = Json.text(constraints.get("max_date"));
        // An ISO date orders lexicographically, which is why the comparison needs no parser.
        if ((min != null && text.value().compareTo(min) < 0) || (max != null && text.value().compareTo(max) > 0)) {
            return problem(id, SubmitProblem.CONSTRAINT);
        }
        return List.of();
    }

    private static List<SubmitProblem> rating(String id, Value answer, Map<String, Object> constraints) {
        if (!(answer instanceof Value.Num number)) {
            return problem(id, SubmitProblem.INVALID_ANSWER);
        }
        Long scale = integer(constraints.get("scale"));
        long highest = scale == null ? 5 : scale;
        BigDecimal value = number.value();
        if (value.signum() < 0 || value.compareTo(BigDecimal.ONE) < 0 || value.compareTo(BigDecimal.valueOf(highest)) > 0) {
            return problem(id, SubmitProblem.CONSTRAINT);
        }
        return List.of();
    }

    private static List<SubmitProblem> nps(String id, Value answer) {
        if (!(answer instanceof Value.Num number)) {
            return problem(id, SubmitProblem.INVALID_ANSWER);
        }
        BigDecimal value = number.value();
        if (value.compareTo(BigDecimal.ZERO) < 0 || value.compareTo(BigDecimal.TEN) > 0) {
            return problem(id, SubmitProblem.CONSTRAINT);
        }
        return List.of();
    }

    private static List<SubmitProblem> singleChoice(String id, Value answer, FlowPlan.Question question) {
        if (!(answer instanceof Value.Text chosen)) {
            return problem(id, SubmitProblem.INVALID_ANSWER);
        }
        return question.options().stream().anyMatch(option -> option.value().equals(chosen.value()))
                ? List.of()
                : problem(id, SubmitProblem.INVALID_ANSWER);
    }

    private static List<SubmitProblem> multiChoice(String id, Value answer, FlowPlan.Question question) {
        if (!(answer instanceof Value.Items items)) {
            return problem(id, SubmitProblem.INVALID_ANSWER);
        }
        Set<String> values = new LinkedHashSet<>();
        for (Value member : items.values()) {
            if (!(member instanceof Value.Text chosen)) {
                return problem(id, SubmitProblem.INVALID_ANSWER);
            }
            boolean known = question.options().stream().anyMatch(option -> option.value().equals(chosen.value()));
            if (!known) {
                return problem(id, SubmitProblem.INVALID_ANSWER);
            }
            values.add(chosen.value());
        }
        Long min = integer(question.constraints().get("min_selected"));
        Long max = integer(question.constraints().get("max_selected"));
        if ((min != null && values.size() < min) || (max != null && values.size() > max)) {
            return problem(id, SubmitProblem.CONSTRAINT);
        }
        return List.of();
    }

    /** A matrix answer is a row-to-column object; every answered cell must name a row and a column of the question. */
    private static List<SubmitProblem> matrix(FlowPlan.Question question, Value answer) {
        Map<String, Object> cells = Json.object(Value.json(answer));
        if (cells == null) {
            return problem(question.id(), SubmitProblem.INVALID_ANSWER);
        }
        Set<String> columns = new LinkedHashSet<>();
        question.columns().forEach(column -> columns.add(column.id()));
        for (Map.Entry<String, Object> cell : cells.entrySet()) {
            if (!question.rowIds().contains(cell.getKey()) || !columns.contains(Json.text(cell.getValue()))) {
                return problem(question.id(), SubmitProblem.INVALID_ANSWER);
            }
        }
        boolean requireAll = !Boolean.FALSE.equals(question.constraints().get("require_all_rows"));
        if (requireAll && !cells.keySet().containsAll(question.rowIds())) {
            return problem(question.id(), SubmitProblem.CONSTRAINT);
        }
        return List.of();
    }

    /** A file answer is a list of upload ids this visit created; a foreign id is refused rather than fetched. */
    private static List<SubmitProblem> file(
            String id, Value answer, Map<String, Object> constraints, Set<String> knownUploads) {
        if (!(answer instanceof Value.Items items)) {
            return problem(id, SubmitProblem.INVALID_ANSWER);
        }
        Long maxFiles = integer(constraints.get("max_files"));
        if (maxFiles != null && items.values().size() > maxFiles) {
            return problem(id, SubmitProblem.CONSTRAINT);
        }
        for (Value member : items.values()) {
            if (!(member instanceof Value.Text upload) || !knownUploads.contains(upload.value())) {
                return problem(id, SubmitProblem.INVALID_UPLOAD);
            }
        }
        return List.of();
    }

    private static List<SubmitProblem> problem(String field, String code) {
        return List.of(new SubmitProblem(field, code));
    }

    private static Long integer(Object raw) {
        return raw instanceof Number number ? number.longValue() : null;
    }

    private static BigDecimal decimal(Object raw) {
        return raw instanceof Number number ? new BigDecimal(number.toString()) : null;
    }

}
