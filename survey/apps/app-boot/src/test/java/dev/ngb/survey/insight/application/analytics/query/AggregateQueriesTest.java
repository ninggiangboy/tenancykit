package dev.ngb.survey.insight.application.analytics.query;

import static org.assertj.core.api.Assertions.assertThat;

import dev.ngb.core.id.TenantId;
import dev.ngb.core.version.ExpectedVersion;
import dev.ngb.survey.authoring.application.draft.command.SaveDraftUseCase;
import dev.ngb.survey.authoring.application.survey.command.CreateSurveyUseCase;
import dev.ngb.survey.authoring.application.version.command.PublishSurveyUseCase;
import dev.ngb.survey.authoring.domain.survey.Survey;
import dev.ngb.survey.collection.application.link.command.CreatePublicLinkUseCase;
import dev.ngb.survey.collection.application.response.command.SubmitResponseUseCase;
import dev.ngb.survey.collection.application.visit.command.OpenLinkUseCase;
import dev.ngb.survey.collection.domain.link.SurveyLink;
import dev.ngb.survey.collection.domain.visit.Visit;
import dev.ngb.survey.support.Documents;
import dev.ngb.survey.support.SurveyIntegrationTest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * The aggregates over two versions: a question v1 and v2 both carry is one row, and a retyped question appears as its
 * own row rather than mixing two shapes.
 */
class AggregateQueriesTest extends SurveyIntegrationTest {

    private static final String ACTOR = "user/tester";

    /** The exact source the last answer used, which is what a source filter must match. */
    private String lastSource;

    @Autowired CreateSurveyUseCase createSurvey;
    @Autowired SaveDraftUseCase saveDraft;
    @Autowired PublishSurveyUseCase publish;
    @Autowired CreatePublicLinkUseCase createPublic;
    @Autowired OpenLinkUseCase openLink;
    @Autowired SubmitResponseUseCase submit;
    @Autowired AggregateQueries aggregates;

    @Test
    void aChoiceQuestionCountsItsOptionsAcrossTwoVersions() {
        Published published = published();
        answerAs(published, Map.of(Documents.CHOICE_QUESTION_ID, "44444444"), "source-a");
        answerAs(published, Map.of(Documents.CHOICE_QUESTION_ID, "55555555"), "source-a");
        answerAs(published, Map.of(Documents.CHOICE_QUESTION_ID, "55555555"), "source-b");

        AggregateView view = inTenant(published.tenant(), () -> aggregates.aggregate(
                published.surveyId(),
                new dev.ngb.survey.collection.api.response.ResponseReader.ResponseFilter(
                        null, null, null, null, null)));

        assertThat(view.responses()).isEqualTo(3);
        assertThat(view.questions())
                .filteredOn(question -> question.type().equals("single_choice"))
                .singleElement()
                .satisfies(question -> assertThat(question.counts()).hasSize(2));
    }

    @Test
    void aNumericQuestionReportsItsMeanAndMedian() {
        Map<String, Object> document = numericDocument();
        Published published = published(document);
        answerAs(published, Map.of(Documents.TEXT_QUESTION_ID, 2), "s");
        answerAs(published, Map.of(Documents.TEXT_QUESTION_ID, 4), "s");
        answerAs(published, Map.of(Documents.TEXT_QUESTION_ID, 9), "s");

        AggregateView view = inTenant(published.tenant(), () -> aggregates.aggregate(
                published.surveyId(), dev.ngb.survey.collection.api.response.ResponseReader.ResponseFilter.all()));

        assertThat(view.questions())
                .filteredOn(question -> question.type().equals("number"))
                .singleElement()
                .satisfies(question -> {
                    assertThat(question.mean()).isEqualByComparingTo("5");
                    assertThat(question.median()).isEqualByComparingTo("4");
                    assertThat(question.distribution()).containsEntry("2", 1L).containsEntry("9", 1L);
                });
    }

    @Test
    void aFilterNarrowsTheAggregateToOneSource() {
        Published published = published();
        answerAs(published, Map.of(Documents.CHOICE_QUESTION_ID, "44444444"), "source-a");
        answerAs(published, Map.of(Documents.CHOICE_QUESTION_ID, "55555555"), "source-b");

        // The answer helper appends a unique suffix to the source, so the filter matches the stored source exactly.
        AggregateView view = inTenant(published.tenant(), () -> aggregates.aggregate(
                published.surveyId(),
                new dev.ngb.survey.collection.api.response.ResponseReader.ResponseFilter(
                        null, null, null, lastSource, null)));

        assertThat(view.responses()).isEqualTo(1);
    }

    private void answerAs(Published published, Map<String, Object> answers, String source) {
        // One public link per source, so each answer needs its own; the exact source is remembered for a filter.
        String exactSource = source + "-" + UUID.randomUUID();
        lastSource = exactSource;
        SurveyLink link = inTenant(published.tenant(), () -> createPublic.execute(published.surveyId(), exactSource));
        Visit visit = inTenant(published.tenant(), () -> openLink.execute(
                        link.id(), new OpenLinkUseCase.ArrivalRequest(null, Map.of(), "Mozilla/5.0", null))
                .orElseThrow()
                .visit());
        inTenant(published.tenant(), () -> submit.execute(
                visit, link, new SubmitResponseUseCase.Submission(answers, "en", "key-" + UUID.randomUUID())));
    }

    private Published published() {
        return published(Documents.valid());
    }

    private Published published(Map<String, Object> document) {
        TenantId tenant = new TenantId(UUID.randomUUID());
        Survey survey = inTenant(tenant, () -> createSurvey.execute(new CreateSurveyUseCase.Command("NPS")));
        inTenant(tenant, () -> saveDraft.execute(
                survey.id(), document, ExpectedVersion.fromIfMatch("\"1\""), ACTOR));
        inTenant(tenant, () -> publish.execute(survey.id(), ExpectedVersion.fromIfMatch("\"2\""), ACTOR));
        return new Published(tenant, survey.id());
    }

    /**
     * A document whose first question is a number, so a mean and a median are observable.
     *
     * <p>The identity flag moves to the second question because the contract allows it only on a text or an email
     * question, and this version keeps one identity question at most.
     */
    private static Map<String, Object> numericDocument() {
        Map<String, Object> document = Documents.valid();
        Map<String, Object> number = Documents.question(document, 0, 0);
        number.put("type", "number");
        number.remove("identity");
        Documents.addQuestion(document, 0, "88888888-8888-7888-8888-888888888888", "short_text");
        Documents.question(document, 0, 2).put("identity", true);
        return document;
    }

    private record Published(TenantId tenant, UUID surveyId) {}
}
