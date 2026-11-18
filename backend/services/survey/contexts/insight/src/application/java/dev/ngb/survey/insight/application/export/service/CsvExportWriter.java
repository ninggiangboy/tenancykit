package dev.ngb.survey.insight.application.export.service;

import dev.ngb.survey.collection.api.response.ResponseReader;
import dev.ngb.survey.collection.api.response.ResponseReader.AnswerRow;
import dev.ngb.survey.collection.api.response.ResponseReader.ResponseFilter;
import dev.ngb.survey.collection.api.response.ResponseReader.ResponseRow;
import dev.ngb.survey.insight.domain.export.CsvWriter;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Renders a survey's completed responses as CSV, one column per question id across every version.
 *
 * <p>The header names each question by its default-locale label from the latest version, because a Tenant reads the
 * file, while the column is keyed by the stable question id so v1's and v2's answers land in the same place. A
 * multi-choice answer joins its values by a semicolon, and every cell is escaped by {@link CsvWriter} so an answer that
 * begins with {@code =} cannot run when the file is opened.
 *
 * <p>Pages of {@code PAGE_SIZE} keep one large survey from being read into memory at once.
 */
public class CsvExportWriter {

    /** How many responses one page reads, which bounds the memory a large export needs. */
    public static final int PAGE_SIZE = 1000;

    private final ResponseReader responses;

    public CsvExportWriter(ResponseReader responses) {
        this.responses = responses;
    }

    /**
     * @param labels the default-locale label per question id, from the latest version
     * @return the whole CSV, or as much as the caller asked to read
     */
    public String render(UUID surveyId, ResponseFilter filter, Map<UUID, String> labels) {
        List<UUID> questionOrder = new ArrayList<>(labels.keySet());
        StringBuilder csv = new StringBuilder();
        csv.append(header(labels, questionOrder));

        int offset = 0;
        while (true) {
            List<ResponseRow> page = responses.responses(surveyId, filter, offset, PAGE_SIZE);
            if (page.isEmpty()) {
                break;
            }
            for (ResponseRow response : page) {
                csv.append(rowOf(response, questionOrder));
            }
            if (page.size() < PAGE_SIZE) {
                break;
            }
            offset += PAGE_SIZE;
        }
        return csv.toString();
    }

    private static String header(Map<UUID, String> labels, List<UUID> questionOrder) {
        List<String> cells = new ArrayList<>();
        cells.add("response_id");
        cells.add("version_id");
        cells.add("submitted_at");
        cells.add("source");
        cells.add("respondent_identity");
        cells.add("external_ref");
        cells.add("score");
        questionOrder.forEach(id -> cells.add(labels.getOrDefault(id, id.toString())));
        return CsvWriter.row(cells);
    }

    private String rowOf(ResponseRow response, List<UUID> questionOrder) {
        Map<UUID, String> answers = new LinkedHashMap<>();
        for (AnswerRow answer : responses.answers(response.id())) {
            answers.merge(answer.questionId(), renderAnswer(answer), (left, right) -> left + CsvWriter.MULTI_CHOICE_SEPARATOR + right);
        }
        List<String> cells = new ArrayList<>();
        cells.add(response.id().toString());
        cells.add(response.versionId().toString());
        cells.add(response.submittedAt() == null ? "" : response.submittedAt().toString());
        cells.add(response.source());
        cells.add(response.respondentIdentity());
        cells.add(response.externalRef());
        cells.add(response.score() == null ? "" : response.score().stripTrailingZeros().toPlainString());
        questionOrder.forEach(id -> cells.add(answers.getOrDefault(id, "")));
        return CsvWriter.row(cells);
    }

    /** The text a typed row shows: a decimal without trailing zeros, a date as itself, anything else its text. */
    private static String renderAnswer(AnswerRow answer) {
        if (answer.numValue() != null) {
            return plain(answer.numValue());
        }
        if (answer.dateValue() != null) {
            return answer.dateValue();
        }
        return answer.textValue() == null ? "" : answer.textValue();
    }

    private static String plain(BigDecimal value) {
        BigDecimal stripped = value.stripTrailingZeros();
        return stripped.signum() == 0 ? "0" : stripped.toPlainString();
    }
}
