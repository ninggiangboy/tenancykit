package dev.ngb.survey.collection.application.link.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.ngb.core.error.NotFoundException;
import dev.ngb.core.id.TenantId;
import dev.ngb.core.version.ExpectedVersion;
import dev.ngb.survey.authoring.application.draft.command.SaveDraftUseCase;
import dev.ngb.survey.authoring.application.survey.command.CreateSurveyUseCase;
import dev.ngb.survey.authoring.application.version.command.PublishSurveyUseCase;
import dev.ngb.survey.authoring.domain.survey.Survey;
import dev.ngb.survey.collection.application.link.exception.LinkRefusedException;
import dev.ngb.survey.collection.application.link.query.LinkQueries;
import dev.ngb.survey.collection.application.link.query.LinkView;
import dev.ngb.survey.collection.application.visit.exception.AlreadyCompletedException;
import dev.ngb.survey.collection.application.visit.command.OpenLinkUseCase;
import dev.ngb.survey.collection.application.visit.service.OpenedLink;
import dev.ngb.survey.collection.domain.link.SurveyLink;
import dev.ngb.survey.support.Documents;
import dev.ngb.survey.support.SurveyIntegrationTest;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Links and the visits they produce: one public link per source, a bulk of personal ones, and the same not-found for
 * every reason a link does not open.
 */
class LinkAndVisitUseCaseTest extends SurveyIntegrationTest {

    private static final String ACTOR = "user/tester";

    @Autowired CreateSurveyUseCase createSurvey;
    @Autowired SaveDraftUseCase saveDraft;
    @Autowired PublishSurveyUseCase publish;
    @Autowired CreatePublicLinkUseCase createPublic;
    @Autowired CreatePersonalLinksUseCase createPersonal;
    @Autowired RevokeLinkUseCase revoke;
    @Autowired OpenLinkUseCase openLink;
    @Autowired LinkQueries linkQueries;

    @Test
    void aPublicLinkIsIssuedOncePerSourceAndItsUrlIsRebuilt() {
        Prepared prepared = published();

        SurveyLink link = inTenant(prepared.tenant(), () -> createPublic.execute(prepared.survey().id(), "newsletter"));
        LinkView view = inTenant(prepared.tenant(), () -> linkQueries.get(link.id()));

        assertThat(view.url()).contains("/p/l/").endsWith("/open");
        assertThatThrownBy(() -> inTenant(
                        prepared.tenant(), () -> createPublic.execute(prepared.survey().id(), "newsletter")))
                .isInstanceOf(LinkRefusedException.class);
    }

    @Test
    void aSurveyThatIsNotOpenIssuesNoLink() {
        Prepared prepared = unprepared();

        assertThatThrownBy(() -> inTenant(
                        prepared.tenant(), () -> createPublic.execute(prepared.survey().id(), "newsletter")))
                .isInstanceOf(LinkRefusedException.class);
    }

    @Test
    void aBulkIssuesOneLinkPerRecipientAndRefusesARepeatedReference() {
        Prepared prepared = published();

        List<SurveyLink> links = inTenant(
                prepared.tenant(),
                () -> createPersonal.execute(
                        prepared.survey().id(),
                        "import",
                        List.of(
                                new CreatePersonalLinksUseCase.Recipient("ref-1", null, null),
                                new CreatePersonalLinksUseCase.Recipient("ref-2", null, null))));

        assertThat(links).hasSize(2).allSatisfy(link -> assertThat(link.source()).isEqualTo("import"));
        assertThatThrownBy(() -> inTenant(
                        prepared.tenant(),
                        () -> createPersonal.execute(
                                prepared.survey().id(),
                                "import",
                                List.of(
                                        new CreatePersonalLinksUseCase.Recipient("ref-3", null, null),
                                        new CreatePersonalLinksUseCase.Recipient("ref-3", null, null)))))
                .isInstanceOf(LinkRefusedException.class);
    }

    @Test
    void openingALinkCreatesAVisitThatRecordsItsSourceAndPinsTheVersion() {
        Prepared prepared = published();
        SurveyLink link = inTenant(prepared.tenant(), () -> createPublic.execute(prepared.survey().id(), "qr"));

        OpenedLink opened = inTenant(prepared.tenant(), () -> openLink
                .execute(
                        link.id(),
                        new OpenLinkUseCase.ArrivalRequest(
                                "https://news.example/some/article?secret=1",
                                Map.of("utm_source", "poster", "utm_medium", "print", "hostile", "x"),
                                "Mozilla/5.0 (iPhone) Safari/605.1",
                                "203.0.113.7"))
                .orElseThrow());

        assertThat(opened.visitToken()).isNotBlank();
        assertThat(opened.visit().source()).isEqualTo("qr");
        assertThat(opened.visit().utm()).containsEntry("utm_source", "poster").doesNotContainKey("hostile");
        assertThat(opened.visit().referrerDomain())
                .as("only the registrable domain survives")
                .isEqualTo("news.example");
        assertThat(opened.visit().deviceClass()).isEqualTo("MOBILE");
        assertThat(opened.visit().versionNo()).isEqualTo(1);
    }

    @Test
    void aRevokedLinkAnswersTheSameNotFoundAsAnUnknownOne() {
        Prepared prepared = published();
        SurveyLink link = inTenant(prepared.tenant(), () -> createPublic.execute(prepared.survey().id(), "qr"));
        inTenant(prepared.tenant(), () -> revoke.execute(link.id()));

        assertThat(inTenant(prepared.tenant(), () -> openLink.execute(
                        link.id(), new OpenLinkUseCase.ArrivalRequest(null, Map.of(), "Mozilla/5.0", null))))
                .isEmpty();
        assertThatThrownBy(() -> inTenant(prepared.tenant(), () -> openLink.execute(
                        UUID.randomUUID(),
                        new OpenLinkUseCase.ArrivalRequest(null, Map.of(), "Mozilla/5.0", null))))
                .as("an unknown link is a 404, not an empty answer")
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void anotherTenantCannotOpenOrReadALink() {
        Prepared prepared = published();
        SurveyLink link = inTenant(prepared.tenant(), () -> createPublic.execute(prepared.survey().id(), "qr"));
        TenantId other = new TenantId(UUID.randomUUID());

        assertThatThrownBy(() -> inTenant(other, () -> openLink.execute(
                        link.id(), new OpenLinkUseCase.ArrivalRequest(null, Map.of(), "Mozilla/5.0", null))))
                .isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> inTenant(other, () -> linkQueries.get(link.id())))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void aPersonalLinkResumesItsOpenVisitRatherThanCreatingASecond() {
        Prepared prepared = published();
        SurveyLink link = inTenant(prepared.tenant(), () -> createPersonal.execute(
                        prepared.survey().id(),
                        "mail",
                        List.of(new CreatePersonalLinksUseCase.Recipient("ref-1", null, null))))
                .get(0);

        OpenedLink first = inTenant(prepared.tenant(), () -> openLink.execute(
                        link.id(), new OpenLinkUseCase.ArrivalRequest(null, Map.of(), "Mozilla/5.0", null)))
                .orElseThrow();
        OpenedLink second = inTenant(prepared.tenant(), () -> openLink.execute(
                        link.id(), new OpenLinkUseCase.ArrivalRequest(null, Map.of(), "Mozilla/5.0", null)))
                .orElseThrow();

        assertThat(second.visit().id()).isEqualTo(first.visit().id());
        assertThat(second.visitToken()).as("the browser already holds its token").isNull();
    }

    @Test
    void anExpiredPersonalLinkOpensNothing() {
        Prepared prepared = published();
        SurveyLink link = inTenant(prepared.tenant(), () -> createPersonal.execute(
                        prepared.survey().id(),
                        "mail",
                        List.of(new CreatePersonalLinksUseCase.Recipient(
                                "ref-1", null, Instant.now().minusSeconds(60)))))
                .get(0);

        assertThat(inTenant(prepared.tenant(), () -> openLink.execute(
                        link.id(), new OpenLinkUseCase.ArrivalRequest(null, Map.of(), "Mozilla/5.0", null))))
                .isEmpty();
    }

    private Prepared published() {
        Prepared prepared = unprepared();
        inTenant(prepared.tenant(), () -> saveDraft.execute(
                prepared.survey().id(), Documents.valid(), ExpectedVersion.fromIfMatch("\"1\""), ACTOR));
        inTenant(prepared.tenant(), () -> publish.execute(
                prepared.survey().id(), ExpectedVersion.fromIfMatch("\"2\""), ACTOR));
        return prepared;
    }

    private Prepared unprepared() {
        TenantId tenant = new TenantId(UUID.randomUUID());
        Survey survey = inTenant(tenant, () -> createSurvey.execute(new CreateSurveyUseCase.Command("NPS")));
        return new Prepared(tenant, survey);
    }

    private record Prepared(TenantId tenant, Survey survey) {}
}
