package dev.ngb.survey.collection.application.response.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.ngb.core.id.TenantId;
import dev.ngb.core.version.ExpectedVersion;
import dev.ngb.survey.authoring.application.draft.command.SaveDraftUseCase;
import dev.ngb.survey.authoring.application.survey.command.CreateSurveyUseCase;
import dev.ngb.survey.authoring.application.version.command.PublishSurveyUseCase;
import dev.ngb.survey.authoring.domain.survey.Survey;
import dev.ngb.survey.collection.application.link.command.CreatePersonalLinksUseCase;
import dev.ngb.survey.collection.application.link.command.CreatePublicLinkUseCase;
import dev.ngb.survey.collection.application.response.exception.SubmitInvalidException;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Collection: the cap, single use and idempotency under concurrency, plus the validation that keeps a hidden answer
 * out of a response.
 *
 * <p>The concurrency cases are the ones that matter. Fifty threads against a cap of ten must complete exactly ten, and
 * a personal link submitted twice concurrently must succeed once: a check-then-write would let both through, which is
 * why the counter's conditional update, the partial unique index and the idempotency key are the database's decision.
 */
class ResponseSubmissionTest extends SurveyIntegrationTest {

    private static final String ACTOR = "user/tester";

    @Autowired CreateSurveyUseCase createSurvey;
    @Autowired SaveDraftUseCase saveDraft;
    @Autowired PublishSurveyUseCase publish;
    @Autowired CreatePublicLinkUseCase createPublic;
    @Autowired CreatePersonalLinksUseCase createPersonal;
    @Autowired OpenLinkUseCase openLink;
    @Autowired SubmitResponseUseCase submit;

    @Test
    void aHiddenQuestionsAnswerIsRefusedWithAnswerNotOnPath() {
        Opened opened = openedFor(documentWithHiddenQuestion());

        assertThatThrownBy(() -> inTenant(opened.tenant(), () -> submit.execute(
                        opened.visit(),
                        opened.link(),
                        submission(Map.of(
                                Documents.TEXT_QUESTION_ID, "no",
                                Documents.CHOICE_QUESTION_ID, "44444444"), "key-1"))))
                .isInstanceOf(SubmitInvalidException.class)
                .satisfies(thrown -> assertThat(((SubmitInvalidException) thrown).problems())
                        .anySatisfy(problem -> {
                            assertThat(problem.field()).isEqualTo(Documents.CHOICE_QUESTION_ID);
                            assertThat(problem.code()).isEqualTo("answer_not_on_path");
                        }));
    }

    @Test
    void aRequiredQuestionBehindAFalseVisibleIfIsNotRequired() {
        Opened opened = openedFor(documentWithHiddenQuestion());

        SubmitResponseUseCase.Submitted submitted = inTenant(opened.tenant(), () -> submit.execute(
                opened.visit(),
                opened.link(),
                submission(Map.of(Documents.TEXT_QUESTION_ID, "no"), "key-2")));

        assertThat(submitted.responseId()).isNotNull();
    }

    @Test
    void aClientSentScoreIsIgnoredAndTheServerComputesItsOwn() {
        Opened opened = openedFor(quizDocument());

        SubmitResponseUseCase.Submitted submitted = inTenant(opened.tenant(), () -> submit.execute(
                opened.visit(),
                opened.link(),
                new SubmitResponseUseCase.Submission(
                        Map.of(Documents.CHOICE_QUESTION_ID, "55555555"), "en", "key-3")));

        // The version scores option "b" five points; a client that sent its own score would be ignored entirely.
        assertThat(submitted.score()).isEqualByComparingTo("5");
    }

    @Test
    void fiftyConcurrentSubmitsAgainstACapOfTenCompleteExactlyTen() throws Exception {
        // One published capped survey, then fifty distinct public links and one visit each, so the race is on the
        // counter rather than on a link.
        Opened template = published(cappedDocument(10));
        List<Opened> opened = new ArrayList<>();
        for (int index = 0; index < 50; index++) {
            opened.add(openedFor(template));
        }

        AtomicInteger completed = new AtomicInteger();
        AtomicInteger refused = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(16);
        CountDownLatch ready = new CountDownLatch(opened.size());
        CountDownLatch go = new CountDownLatch(1);
        try {
            for (Opened one : opened) {
                pool.execute(() -> {
                    ready.countDown();
                    await(go);
                    try {
                        inTenant(one.tenant(), () -> submit.execute(
                                one.visit(), one.link(),
                                submission(Map.of(Documents.TEXT_QUESTION_ID, "x"), "cap-key-" + one.visit().id())));
                        completed.incrementAndGet();
                    } catch (RuntimeException lost) {
                        // A concurrent loss of the counter update is a refusal from the caller's point of view.
                        refused.incrementAndGet();
                    }
                });
            }
            ready.await(30, TimeUnit.SECONDS);
            go.countDown();
        } finally {
            pool.shutdown();
            assertThat(pool.awaitTermination(60, TimeUnit.SECONDS)).isTrue();
        }

        assertThat(completed.get())
                .as("a conditional counter update serialises the increment, so the cap holds under a burst")
                .isEqualTo(10);
        assertThat(refused.get()).isEqualTo(40);
    }

    @Test
    void aPersonalLinkCannotCompleteTwiceEvenUnderARace() throws Exception {
        Opened opened = openedForPersonalLink(documentWithHiddenQuestion());

        AtomicInteger succeeded = new AtomicInteger();
        AtomicInteger conflicted = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch go = new CountDownLatch(1);
        try {
            for (int index = 0; index < 2; index++) {
                String key = "personal-key-" + index;
                pool.execute(() -> {
                    await(go);
                    try {
                        inTenant(opened.tenant(), () -> submit.execute(
                                opened.visit(),
                                opened.link(),
                                submission(Map.of(Documents.TEXT_QUESTION_ID, "no"), key)));
                        succeeded.incrementAndGet();
                    } catch (RuntimeException conflict) {
                        // A second completion on one personal link is a conflict from the caller's point of view.
                        conflicted.incrementAndGet();
                    }
                });
            }
            go.countDown();
        } finally {
            pool.shutdown();
            assertThat(pool.awaitTermination(60, TimeUnit.SECONDS)).isTrue();
        }

        assertThat(succeeded.get())
                .as("the partial unique index on a completed personal link is what makes single use hold")
                .isEqualTo(1);
        assertThat(conflicted.get()).isEqualTo(1);
    }

    @Test
    void aRepeatedIdempotencyKeyReturnsTheFirstResult() {
        Opened opened = openedFor(documentWithHiddenQuestion());
        Map<String, Object> answers = Map.of(Documents.TEXT_QUESTION_ID, "no");

        SubmitResponseUseCase.Submitted first = inTenant(opened.tenant(), () -> submit.execute(
                opened.visit(), opened.link(), submission(answers, "same-key")));
        SubmitResponseUseCase.Submitted second = inTenant(opened.tenant(), () -> submit.execute(
                opened.visit(), opened.link(), submission(answers, "same-key")));

        assertThat(second.responseId()).isEqualTo(first.responseId());
        assertThat(second.replayed()).isTrue();
    }

    /** Waits for a latch, turning the checked interruption into a failure the pool reports. */
    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(interrupted);
        }
    }

    private SubmitResponseUseCase.Submission submission(Map<String, Object> answers, String key) {
        return new SubmitResponseUseCase.Submission(answers, "en", key);
    }

    /** A published survey with one public link, opened once. */
    private Opened openedFor(Map<String, Object> document) {
        return openedFor(published(document));
    }

    /** One more opening of the same published survey, through a fresh public link. */
    private Opened openedFor(Opened template) {
        SurveyLink link = inTenant(template.tenant(), () -> createPublic.execute(
                template.surveyId(), "src-" + UUID.randomUUID()));
        Visit visit = inTenant(template.tenant(), () -> openLink.execute(
                        link.id(), new OpenLinkUseCase.ArrivalRequest(null, Map.of(), "Mozilla/5.0", null))
                .orElseThrow()
                .visit());
        return new Opened(template.tenant(), template.surveyId(), link, visit);
    }

    /** A published survey with its first public link, opened once. */
    private Opened published(Map<String, Object> document) {
        TenantId tenant = new TenantId(UUID.randomUUID());
        Survey survey = inTenant(tenant, () -> createSurvey.execute(new CreateSurveyUseCase.Command("NPS")));
        inTenant(tenant, () -> saveDraft.execute(
                survey.id(), document, ExpectedVersion.fromIfMatch("\"1\""), ACTOR));
        inTenant(tenant, () -> publish.execute(
                survey.id(), ExpectedVersion.fromIfMatch("\"2\""), ACTOR));
        SurveyLink link = inTenant(tenant, () -> createPublic.execute(survey.id(), "qr"));
        Visit visit = inTenant(tenant, () -> openLink.execute(
                        link.id(), new OpenLinkUseCase.ArrivalRequest(null, Map.of(), "Mozilla/5.0", null))
                .orElseThrow()
                .visit());
        return new Opened(tenant, survey.id(), link, visit);
    }

    /** A published survey reached through one personal link, opened once. */
    private Opened openedForPersonalLink(Map<String, Object> document) {
        TenantId tenant = new TenantId(UUID.randomUUID());
        Survey survey = inTenant(tenant, () -> createSurvey.execute(new CreateSurveyUseCase.Command("NPS")));
        inTenant(tenant, () -> saveDraft.execute(
                survey.id(), document, ExpectedVersion.fromIfMatch("\"1\""), ACTOR));
        inTenant(tenant, () -> publish.execute(
                survey.id(), ExpectedVersion.fromIfMatch("\"2\""), ACTOR));
        SurveyLink link = inTenant(tenant, () -> createPersonal.execute(
                        survey.id(),
                        "mail",
                        List.of(new CreatePersonalLinksUseCase.Recipient("ref-1", null, null))))
                .get(0);
        Visit visit = inTenant(tenant, () -> openLink.execute(
                        link.id(), new OpenLinkUseCase.ArrivalRequest(null, Map.of(), "Mozilla/5.0", null))
                .orElseThrow()
                .visit());
        return new Opened(tenant, survey.id(), link, visit);
    }

    /** A document whose choice question is hidden whenever the text question is not "yes". */
    private static Map<String, Object> documentWithHiddenQuestion() {
        Map<String, Object> document = Documents.valid();
        Map<String, Object> visibleIf = new LinkedHashMap<>();
        visibleIf.put("op", "eq");
        visibleIf.put("args", List.of(Map.of("answer", Documents.TEXT_QUESTION_ID), "yes"));
        Documents.question(document, 0, 1).put("visible_if", visibleIf);
        return document;
    }

    /** A document with one scored choice, so the server's own score is observable. */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> quizDocument() {
        Map<String, Object> document = Documents.valid();
        Map<String, Object> settings = Documents.settings(document);
        settings.put("quiz", Map.of("enabled", true, "return_score", true));
        Documents.question(document, 0, 1).put("required", true);
        List<Object> options = Documents.options(Documents.question(document, 0, 1));
        ((Map<String, Object>) options.get(1)).put("score", 5);
        return document;
    }

    /** A document with a response cap, which is what the concurrency case measures. */
    private static Map<String, Object> cappedDocument(int cap) {
        Map<String, Object> document = Documents.valid();
        Documents.settings(document).put("response_cap", cap);
        return document;
    }

    /** One opened link: the tenant, the survey, the link and the visit it produced. */
    private record Opened(TenantId tenant, UUID surveyId, SurveyLink link, Visit visit) {}
}
