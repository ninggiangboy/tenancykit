package dev.ngb.survey.authoring.application.version.command;

import static dev.ngb.survey_jooq.Tables.QUESTION_LINEAGE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.ngb.core.error.NotFoundException;
import dev.ngb.core.error.PreconditionFailedException;
import dev.ngb.core.id.TenantId;
import dev.ngb.core.runtime.UnitOfWork;
import dev.ngb.core.version.ExpectedVersion;
import dev.ngb.survey.authoring.api.document.ValidationCode;
import dev.ngb.survey.authoring.application.draft.command.SaveDraftUseCase;
import dev.ngb.survey.authoring.application.draft.exception.DraftInvalidException;
import dev.ngb.survey.authoring.application.draft.query.DraftQueries;
import dev.ngb.survey.authoring.application.survey.command.CreateSurveyUseCase;
import dev.ngb.survey.authoring.application.survey.query.SurveyQueries;
import dev.ngb.survey.authoring.application.version.query.VersionQueries;
import dev.ngb.survey.authoring.domain.survey.Survey;
import dev.ngb.survey.authoring.domain.survey.SurveyStatus;
import dev.ngb.survey.authoring.domain.version.SurveyVersion;
import dev.ngb.survey.support.Documents;
import dev.ngb.survey.support.SurveyIntegrationTest;
import java.util.Map;
import java.util.UUID;
import org.jooq.DSLContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Publishing: the first publish opens the survey, a retyped question gets a new id and a lineage row, an incomplete
 * translation set is listed in full, and the tag a publish consumes cannot publish the same document twice.
 */
class PublishSurveyUseCaseTest extends SurveyIntegrationTest {

    private static final String ACTOR = "user/tester";
    private static final UUID CHOICE = UUID.fromString(Documents.CHOICE_QUESTION_ID);

    @Autowired
    CreateSurveyUseCase createSurvey;
    @Autowired
    SaveDraftUseCase saveDraft;
    @Autowired
    PublishSurveyUseCase publish;
    @Autowired
    SurveyQueries surveyQueries;
    @Autowired
    DraftQueries draftQueries;
    @Autowired
    VersionQueries versionQueries;
    @Autowired
    DSLContext dsl;
    @Autowired
    UnitOfWork unitOfWork;

    @Test
    void theFirstPublishOpensTheSurveyAndNumbersTheVersion() {
        Publishable publishable = prepared();

        SurveyVersion version = publish(publishable, "\"2\"");

        assertThat(version.versionNo()).isEqualTo(1);
        var survey = surveyOf(publishable);
        assertThat(survey.status()).isEqualTo(SurveyStatus.OPEN);
        assertThat(survey.currentVersionId()).isEqualTo(version.id());
        assertThat(survey.currentVersionNo()).isEqualTo(1);
        assertThat(draftTag(publishable))
                .as("a publish consumes the tag it compared")
                .isEqualTo(3);
        assertThat(inTenant(publishable.tenant(), () -> versionQueries.get(publishable.survey().id(), 1)).versionNo())
                .isEqualTo(1);
    }

    @Test
    void aRetypedQuestionGetsANewIdAndALineageRow() {
        Publishable publishable = prepared();
        publish(publishable, "\"2\"");

        Map<String, Object> draft = inTenant(publishable.tenant(), () -> draftQueries.get(publishable.survey().id()))
                .document();
        Map<String, Object> question = Documents.question(draft, 0, 1);
        question.put("type", "number");
        Documents.removeOptions(question, draft);
        inTenant(publishable.tenant(), () -> saveDraft.execute(
                publishable.survey().id(), draft, ExpectedVersion.fromIfMatch("\"3\""), ACTOR));

        SurveyVersion second = publish(publishable, "\"4\"");

        String newId = (String) Documents.question(second.document(), 0, 1).get("id");
        assertThat(second.versionNo()).isEqualTo(2);
        assertThat(newId).isNotEqualTo(Documents.CHOICE_QUESTION_ID);
        assertThat(lineageRecorded(publishable, UUID.fromString(newId)))
                .as("the new id records the one it replaced and the version it first appears in")
                .isTrue();
        assertThat(Documents.translations(second.document(), "en"))
                .as("the translation keys follow the retyped question")
                .containsKey("question." + newId + ".label")
                .doesNotContainKey("question." + Documents.CHOICE_QUESTION_ID + ".label");
        assertThat(Documents.question(
                                inTenant(publishable.tenant(), () -> draftQueries.get(publishable.survey().id()))
                                        .document(),
                                0,
                                1)
                        .get("id"))
                .as("the draft carries the new id, so the next publish does not retype it again")
                .isEqualTo(newId);
    }

    @Test
    void aPublishListsEveryMissingTranslationAtOnce() {
        Publishable publishable = unprepared();
        Map<String, Object> document = Documents.valid();
        Documents.translations(document, "en").remove("question." + Documents.TEXT_QUESTION_ID + ".help");
        Documents.translations(document, "en").remove("option." + Documents.OPTION_A_ID + ".label");
        save(publishable, document, "\"1\"");

        assertThatThrownBy(() -> publish(publishable, "\"2\""))
                .isInstanceOf(DraftInvalidException.class)
                .satisfies(thrown -> {
                    var errors = ((DraftInvalidException) thrown).errors();
                    assertThat(errors).hasSize(2);
                    assertThat(errors).allMatch(error -> error.code() == ValidationCode.TRANSLATION_MISSING);
                });
        assertThat(surveyOf(publishable).status())
                .as("a failed publish changes nothing")
                .isEqualTo(SurveyStatus.DRAFT);
    }

    @Test
    void theTagAPublishComparedCannotPublishTwice() {
        Publishable publishable = prepared();
        publish(publishable, "\"2\"");

        assertThatThrownBy(() -> publish(publishable, "\"2\""))
                .isInstanceOf(PreconditionFailedException.class);
        assertThat(inTenant(publishable.tenant(), () -> versionQueries.list(publishable.survey().id())))
                .hasSize(1);
    }

    @Test
    void oneTenantCannotPublishAnothersSurvey() {
        Publishable publishable = prepared();
        TenantId other = new TenantId(UUID.randomUUID());

        assertThatThrownBy(() -> inTenant(other, () -> publish.execute(
                        publishable.survey().id(), ExpectedVersion.fromIfMatch("\"2\""), ACTOR)))
                .isInstanceOf(NotFoundException.class);
    }

    private boolean lineageRecorded(Publishable publishable, UUID questionId) {
        return inTenant(publishable.tenant(), () -> unitOfWork.transactional(() -> dsl.fetchCount(
                        QUESTION_LINEAGE,
                        QUESTION_LINEAGE.SURVEY_ID.eq(publishable.survey().id())
                                .and(QUESTION_LINEAGE.QUESTION_ID.eq(questionId))
                                .and(QUESTION_LINEAGE.REPLACES.eq(CHOICE))
                                .and(QUESTION_LINEAGE.FIRST_VERSION_NO.eq(2)))
                == 1));
    }

    private long draftTag(Publishable publishable) {
        return inTenant(publishable.tenant(), () -> draftQueries.get(publishable.survey().id())).etag();
    }

    private dev.ngb.survey.authoring.application.survey.query.SurveyView surveyOf(Publishable publishable) {
        return inTenant(publishable.tenant(), () -> surveyQueries.get(publishable.survey().id()));
    }

    /** A survey with the valid document saved at tag 2. */
    private Publishable prepared() {
        Publishable publishable = unprepared();
        save(publishable, Documents.valid(), "\"1\"");
        return publishable;
    }

    private Publishable unprepared() {
        TenantId tenant = new TenantId(UUID.randomUUID());
        Survey survey = inTenant(tenant, () -> createSurvey.execute(new CreateSurveyUseCase.Command("NPS")));
        return new Publishable(tenant, survey);
    }

    private void save(Publishable publishable, Map<String, Object> document, String tag) {
        inTenant(publishable.tenant(), () -> saveDraft.execute(
                publishable.survey().id(), document, ExpectedVersion.fromIfMatch(tag), ACTOR));
    }

    private SurveyVersion publish(Publishable publishable, String tag) {
        return inTenant(publishable.tenant(), () -> publish.execute(
                publishable.survey().id(), ExpectedVersion.fromIfMatch(tag), ACTOR));
    }

    private record Publishable(TenantId tenant, Survey survey) {}
}
