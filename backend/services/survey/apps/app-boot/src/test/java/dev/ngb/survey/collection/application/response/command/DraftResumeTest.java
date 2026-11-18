package dev.ngb.survey.collection.application.response.command;

import static org.assertj.core.api.Assertions.assertThat;

import dev.ngb.core.id.TenantId;
import dev.ngb.core.version.ExpectedVersion;
import dev.ngb.survey.authoring.application.draft.command.SaveDraftUseCase;
import dev.ngb.survey.authoring.application.survey.command.CreateSurveyUseCase;
import dev.ngb.survey.authoring.application.version.command.PublishSurveyUseCase;
import dev.ngb.survey.authoring.domain.survey.Survey;
import dev.ngb.survey.collection.application.link.command.CreatePublicLinkUseCase;
import dev.ngb.survey.collection.application.visit.command.OpenLinkUseCase;
import dev.ngb.survey.collection.domain.link.SurveyLink;
import dev.ngb.survey.collection.domain.visit.Visit;
import dev.ngb.survey.support.Documents;
import dev.ngb.survey.support.SurveyIntegrationTest;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * A visit pins the version it opened, so publishing v2 never changes the questions under a respondent who is already
 * answering. This is what makes "resume where you stopped" true rather than merely "resume".
 */
class DraftResumeTest extends SurveyIntegrationTest {

    private static final String ACTOR = "user/tester";

    @Autowired CreateSurveyUseCase createSurvey;
    @Autowired
    @org.springframework.beans.factory.annotation.Qualifier("saveDraftUseCase")
    SaveDraftUseCase saveDraft;
    @Autowired PublishSurveyUseCase publish;
    @Autowired CreatePublicLinkUseCase createPublic;
    @Autowired OpenLinkUseCase openLink;
    @Autowired
    @org.springframework.beans.factory.annotation.Qualifier("saveResponseDraftUseCase")
    dev.ngb.survey.collection.application.response.command.SaveDraftUseCase saveResponseDraft;
    @Autowired dev.ngb.survey.collection.application.visit.service.VisitLookup visitLookup;

    @Test
    void aVisitOpenedBeforeV2StillReadsAndSubmitsAgainstV1() {
        TenantId tenant = new TenantId(UUID.randomUUID());
        Survey survey = inTenant(tenant, () -> createSurvey.execute(new CreateSurveyUseCase.Command("NPS")));
        inTenant(tenant, () -> saveDraft.execute(
                survey.id(), Documents.valid(), ExpectedVersion.fromIfMatch("\"1\""), ACTOR));
        inTenant(tenant, () -> publish.execute(survey.id(), ExpectedVersion.fromIfMatch("\"2\""), ACTOR));

        SurveyLink link = inTenant(tenant, () -> createPublic.execute(survey.id(), "qr"));
        Visit opened = inTenant(tenant, () -> openLink.execute(
                        link.id(), new OpenLinkUseCase.ArrivalRequest(null, Map.of(), "Mozilla/5.0", null))
                .orElseThrow()
                .visit());
        assertThat(opened.versionNo()).isEqualTo(1);

        // A second version is published while the respondent is still answering.
        Map<String, Object> v2 = Documents.valid();
        Documents.addQuestion(v2, 0, "99999999-9999-7999-8999-999999999999", "long_text");
        inTenant(tenant, () -> saveDraft.execute(
                survey.id(), v2, ExpectedVersion.fromIfMatch("\"3\""), ACTOR));
        inTenant(tenant, () -> publish.execute(survey.id(), ExpectedVersion.fromIfMatch("\"4\""), ACTOR));

        Visit resumed = inTenant(tenant, () -> visitLookup.byToken(opened.tokenHash()).orElseThrow());

        assertThat(resumed.versionNo())
                .as("the visit keeps the version it opened, so the questions never change under the respondent")
                .isEqualTo(1);
    }

    @Test
    void aSavedDraftIsRecordedAgainstTheVisitsVersion() {
        TenantId tenant = new TenantId(UUID.randomUUID());
        Survey survey = inTenant(tenant, () -> createSurvey.execute(new CreateSurveyUseCase.Command("NPS")));
        inTenant(tenant, () -> saveDraft.execute(
                survey.id(), Documents.valid(), ExpectedVersion.fromIfMatch("\"1\""), ACTOR));
        inTenant(tenant, () -> publish.execute(survey.id(), ExpectedVersion.fromIfMatch("\"2\""), ACTOR));
        SurveyLink link = inTenant(tenant, () -> createPublic.execute(survey.id(), "qr"));
        Visit visit = inTenant(tenant, () -> openLink.execute(
                        link.id(), new OpenLinkUseCase.ArrivalRequest(null, Map.of(), "Mozilla/5.0", null))
                .orElseThrow()
                .visit());

        var draft = inTenant(tenant, () -> saveResponseDraft.execute(
                visit, link, Map.of(Documents.TEXT_QUESTION_ID, "half")));

        assertThat(draft.versionId()).isEqualTo(visit.versionId());
        assertThat(draft.answers()).containsEntry(Documents.TEXT_QUESTION_ID, "half");
    }
}
