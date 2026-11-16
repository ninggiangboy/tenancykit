package dev.ngb.survey.authoring.application.draft.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.ngb.core.error.NotFoundException;
import dev.ngb.core.error.PreconditionFailedException;
import dev.ngb.core.id.TenantId;
import dev.ngb.core.version.ExpectedVersion;
import dev.ngb.survey.authoring.application.draft.exception.DraftInvalidException;
import dev.ngb.survey.authoring.application.draft.query.DraftQueries;
import dev.ngb.survey.authoring.application.draft.query.DraftView;
import dev.ngb.survey.authoring.application.survey.command.CreateSurveyUseCase;
import dev.ngb.survey.authoring.domain.draft.SurveyDraft;
import dev.ngb.survey.authoring.domain.survey.Survey;
import dev.ngb.survey.support.Documents;
import dev.ngb.survey.support.SurveyIntegrationTest;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Saving a draft: the tag moves one step per accepted write, a stale tag changes nothing, and two editors holding the
 * same tag cannot both win. The rows are the real ones; row-level security is what makes a foreign survey a
 * {@code 404}.
 */
class SaveDraftUseCaseTest extends SurveyIntegrationTest {

    private static final String ACTOR = "user/tester";

    @Autowired
    CreateSurveyUseCase createSurvey;
    @Autowired
    SaveDraftUseCase saveDraft;
    @Autowired
    DraftQueries draftQueries;

    @Test
    void aNewSurveyStartsWithAMinimalDraft() {
        TenantId tenant = newTenant();
        Survey survey = survey(tenant, "NPS");

        DraftView draft = inTenant(tenant, () -> draftQueries.get(survey.id()));

        assertThat(draft.etag()).isEqualTo(1);
        assertThat(draft.document()).containsKeys("settings", "pages", "computed", "scoring", "translations");
        assertThat(Documents.pages(draft.document())).isEmpty();
    }

    @Test
    void aSaveAnswersTheStoredDocumentAndTheNextTag() {
        TenantId tenant = newTenant();
        Survey survey = survey(tenant, "NPS");

        SurveyDraft saved = inTenant(tenant, () -> saveDraft.execute(
                survey.id(), Documents.valid(), ExpectedVersion.any(), ACTOR));

        assertThat(saved.etag()).isEqualTo(2);
        assertThat(Documents.page(saved.document(), 0).get("id"))
                .as("the ids the client sent are kept")
                .isEqualTo(Documents.PAGE_ID);
        assertThat(inTenant(tenant, () -> draftQueries.get(survey.id())).etag())
                .isEqualTo(2);
    }

    @Test
    void aStaleTagIsRejectedAndChangesNothing() {
        TenantId tenant = newTenant();
        Survey survey = survey(tenant, "NPS");
        inTenant(tenant, () -> saveDraft.execute(
                survey.id(), Documents.valid(), ExpectedVersion.any(), ACTOR));

        Map<String, Object> other = Documents.valid();
        Documents.translations(other, "en").put("survey.title", "Changed");

        assertThatThrownBy(() -> inTenant(tenant, () -> saveDraft.execute(
                        survey.id(), other, ExpectedVersion.fromIfMatch("\"1\""), ACTOR)))
                .isInstanceOf(PreconditionFailedException.class)
                .satisfies(thrown -> assertThat(((PreconditionFailedException) thrown).currentVersion())
                        .isEqualTo(2));
        assertThat(Documents.translations(
                        inTenant(tenant, () -> draftQueries.get(survey.id())).document(), "en"))
                .containsEntry("survey.title", "Test survey");
    }

    @Test
    void twoEditorsWithTheSameTagDoNotBothWin() throws Exception {
        TenantId tenant = newTenant();
        Survey survey = survey(tenant, "NPS");
        Map<String, Object> first = Documents.valid();
        Documents.translations(first, "en").put("survey.title", "First");
        Map<String, Object> second = Documents.valid();
        Documents.translations(second, "en").put("survey.title", "Second");

        List<Boolean> outcomes = race(
                () -> save(survey, first),
                () -> save(survey, second));

        assertThat(outcomes).containsExactlyInAnyOrder(true, false);
        DraftView stored = inTenant(tenant, () -> draftQueries.get(survey.id()));
        assertThat(stored.etag()).as("exactly one write moved the tag").isEqualTo(2);
        String winner = outcomes.get(0) ? "First" : "Second";
        assertThat(Documents.translations(stored.document(), "en")).containsEntry("survey.title", winner);
    }

    @Test
    void aStructurallyInvalidDocumentIsRefusedBeforeItIsStored() {
        TenantId tenant = newTenant();
        Survey survey = survey(tenant, "NPS");
        Map<String, Object> broken = Documents.valid();
        Documents.question(broken, 0, 1).put("type", "smell");

        assertThatThrownBy(() -> inTenant(tenant, () -> saveDraft.execute(
                        survey.id(), broken, ExpectedVersion.any(), ACTOR)))
                .isInstanceOf(DraftInvalidException.class)
                .satisfies(thrown -> assertThat(((DraftInvalidException) thrown).errors()).isNotEmpty());
        assertThat(inTenant(tenant, () -> draftQueries.get(survey.id())).etag()).isEqualTo(1);
    }

    @Test
    void oneTenantCannotReadOrWriteAnothersDraft() {
        TenantId tenantA = newTenant();
        TenantId tenantB = newTenant();
        Survey survey = survey(tenantA, "NPS");

        assertThatThrownBy(() -> inTenant(tenantB, () -> draftQueries.get(survey.id())))
                .isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> inTenant(tenantB, () -> saveDraft.execute(
                        survey.id(), Documents.valid(), ExpectedVersion.any(), ACTOR)))
                .isInstanceOf(NotFoundException.class);
    }

    private boolean save(Survey survey, Map<String, Object> document) {
        try {
            inTenant(new TenantId(survey.tenantId().value()), () -> saveDraft.execute(
                    survey.id(), document, ExpectedVersion.fromIfMatch("\"1\""), ACTOR));
            return true;
        } catch (PreconditionFailedException lost) {
            return false;
        }
    }

    private static List<Boolean> race(java.util.concurrent.Callable<Boolean> first,
            java.util.concurrent.Callable<Boolean> second) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<Boolean> one = pool.submit(() -> {
                start.await();
                return first.call();
            });
            Future<Boolean> two = pool.submit(() -> {
                start.await();
                return second.call();
            });
            start.countDown();
            return List.of(one.get(30, TimeUnit.SECONDS), two.get(30, TimeUnit.SECONDS));
        } finally {
            pool.shutdownNow();
        }
    }

    private Survey survey(TenantId tenant, String name) {
        return inTenant(tenant, () -> createSurvey.execute(new CreateSurveyUseCase.Command(name)));
    }

    private static TenantId newTenant() {
        return new TenantId(UUID.randomUUID());
    }
}
