package dev.ngb.survey.insight.application.funnel.query;

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
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** The funnel splits by source and leaves a bot out of every count. */
class FunnelQueriesTest extends SurveyIntegrationTest {

    private static final String ACTOR = "user/tester";
    private static final String HUMAN = "Mozilla/5.0 (Macintosh) Chrome/120";

    @Autowired CreateSurveyUseCase createSurvey;
    @Autowired SaveDraftUseCase saveDraft;
    @Autowired PublishSurveyUseCase publish;
    @Autowired CreatePublicLinkUseCase createPublic;
    @Autowired OpenLinkUseCase openLink;
    @Autowired SubmitResponseUseCase submit;
    @Autowired FunnelQueries funnels;

    @Test
    void theFunnelSplitsBySourceAndExcludesBots() {
        Published published = published();

        open(published, "newsletter", HUMAN);
        open(published, "newsletter", HUMAN);
        complete(published, "newsletter");
        open(published, "qr", HUMAN);
        open(published, "qr", "Slackbot-LinkExpanding");

        FunnelView view = inTenant(published.tenant(), () -> funnels.funnel(
                published.surveyId(), FunnelView.GroupBy.SOURCE, null, null));

        // Two newsletter openings, one completed newsletter visit, one qr opening: the Slackbot preview is dropped.
        assertThat(view.opened()).as("a link preview is not a respondent").isEqualTo(4);
        assertThat(view.completed()).isEqualTo(1);
        // Every opening is its own source (one public link per source), so the rows are summed by prefix.
        var newsletter = view.rows().stream().filter(row -> row.key().startsWith("newsletter")).toList();
        var qr = view.rows().stream().filter(row -> row.key().startsWith("qr")).toList();

        assertThat(newsletter).as("the completed visit is among them").hasSize(3);
        assertThat(newsletter.stream().mapToLong(FunnelView.Row::opened).sum()).isEqualTo(3);
        assertThat(newsletter.stream().mapToLong(FunnelView.Row::completed).sum()).isEqualTo(1);
        assertThat(qr)
                .as("the Slackbot preview's row is gone")
                .singleElement()
                .satisfies(row -> assertThat(row.opened()).isEqualTo(1));
    }

    private void open(Published published, String source, String userAgent) {
        SurveyLink link = inTenant(published.tenant(), () -> createPublic.execute(
                published.surveyId(), source + "-" + UUID.randomUUID()));
        inTenant(published.tenant(), () -> openLink.execute(
                link.id(), new OpenLinkUseCase.ArrivalRequest(null, Map.of(), userAgent, null)));
    }

    private void complete(Published published, String source) {
        SurveyLink link = inTenant(published.tenant(), () -> createPublic.execute(
                published.surveyId(), source + "-done-" + UUID.randomUUID()));
        Visit visit = inTenant(published.tenant(), () -> openLink.execute(
                        link.id(), new OpenLinkUseCase.ArrivalRequest(null, Map.of(), HUMAN, null))
                .orElseThrow()
                .visit());
        inTenant(published.tenant(), () -> submit.execute(
                visit,
                link,
                new SubmitResponseUseCase.Submission(
                        Map.of(Documents.TEXT_QUESTION_ID, "yes"), "en", "key-" + UUID.randomUUID())));
    }

    private Published published() {
        TenantId tenant = new TenantId(UUID.randomUUID());
        Survey survey = inTenant(tenant, () -> createSurvey.execute(new CreateSurveyUseCase.Command("NPS")));
        inTenant(tenant, () -> saveDraft.execute(
                survey.id(), Documents.valid(), ExpectedVersion.fromIfMatch("\"1\""), ACTOR));
        inTenant(tenant, () -> publish.execute(survey.id(), ExpectedVersion.fromIfMatch("\"2\""), ACTOR));
        return new Published(tenant, survey.id());
    }

    private record Published(TenantId tenant, UUID surveyId) {}
}
