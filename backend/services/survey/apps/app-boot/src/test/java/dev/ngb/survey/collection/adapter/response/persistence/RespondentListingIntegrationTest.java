package dev.ngb.survey.collection.adapter.response.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import dev.ngb.core.id.TenantId;
import dev.ngb.core.query.Page;
import dev.ngb.core.runtime.UnitOfWork;
import dev.ngb.core.version.ExpectedVersion;
import dev.ngb.survey.authoring.application.draft.command.SaveDraftUseCase;
import dev.ngb.survey.authoring.application.survey.command.CreateSurveyUseCase;
import dev.ngb.survey.authoring.application.version.command.PublishSurveyUseCase;
import dev.ngb.survey.authoring.domain.survey.Survey;
import dev.ngb.survey.collection.api.response.RespondentListing;
import dev.ngb.survey.collection.api.response.ResponseReader;
import dev.ngb.survey.collection.application.link.command.CreatePersonalLinksUseCase;
import dev.ngb.survey.collection.application.response.command.SubmitResponseUseCase;
import dev.ngb.survey.collection.application.visit.command.OpenLinkUseCase;
import dev.ngb.survey.collection.domain.link.SurveyLink;
import dev.ngb.survey.collection.domain.visit.Visit;
import dev.ngb.survey.support.Documents;
import dev.ngb.survey.support.ListQueries;
import dev.ngb.survey.support.SurveyIntegrationTest;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * The respondents listing carries both keys a resend joins a mailroom group on: the identity the response was submitted
 * with and the personal link's reference, which is the Tenant's own key for the person.
 */
class RespondentListingIntegrationTest extends SurveyIntegrationTest {

    private static final String ACTOR = "user/tester";

    @Autowired CreateSurveyUseCase createSurvey;
    @Autowired SaveDraftUseCase saveDraft;
    @Autowired PublishSurveyUseCase publish;
    @Autowired CreatePersonalLinksUseCase createPersonal;
    @Autowired OpenLinkUseCase openLink;
    @Autowired SubmitResponseUseCase submit;
    @Autowired UnitOfWork unitOfWork;
    @Autowired ResponseReader responses;

    @Test
    void aRespondentRowCarriesTheExternalRefTheTenantKnowsThemBy() {
        TenantId tenant = new TenantId(UUID.randomUUID());
        Survey survey = inTenant(tenant, () -> createSurvey.execute(new CreateSurveyUseCase.Command("NPS")));
        inTenant(tenant, () -> saveDraft.execute(
                survey.id(), Documents.valid(), ExpectedVersion.fromIfMatch("\"1\""), ACTOR));
        inTenant(tenant, () -> publish.execute(survey.id(), ExpectedVersion.fromIfMatch("\"2\""), ACTOR));
        SurveyLink link = inTenant(tenant, () -> createPersonal.execute(
                        survey.id(), "mail", List.of(new CreatePersonalLinksUseCase.Recipient("ref-1", null, null))))
                .get(0);
        Visit visit = inTenant(tenant, () -> openLink.execute(
                        link.id(), new OpenLinkUseCase.ArrivalRequest(null, Map.of(), "Mozilla/5.0", null))
                .orElseThrow()
                .visit());
        inTenant(tenant, () -> submit.execute(
                visit, link,
                new SubmitResponseUseCase.Submission(Map.of(Documents.TEXT_QUESTION_ID, "hello"), "en", "key-1")));

        // The read runs in a unit of work because row-level security reads the tenant of the open transaction.
        Page<ResponseReader.RespondentRow> page = inTenant(tenant, () -> unitOfWork.transactional(
                () -> responses.respondents(survey.id(), ListQueries.of(RespondentListing.SCHEMA, "limit", "10"))));

        assertThat(page.data()).singleElement().satisfies(row -> {
            assertThat(row.responseId()).isNotNull();
            assertThat(row.externalRef()).isEqualTo("ref-1");
        });
    }
}
