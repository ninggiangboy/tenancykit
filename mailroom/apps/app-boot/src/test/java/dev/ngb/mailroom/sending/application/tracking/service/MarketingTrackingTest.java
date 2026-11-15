package dev.ngb.mailroom.sending.application.tracking.service;

import static org.assertj.core.api.Assertions.assertThat;

import dev.ngb.core.id.TenantId;
import dev.ngb.mailroom.content.api.render.RenderedEmail;
import dev.ngb.mailroom.sending.application.email.port.TrackingHook;
import dev.ngb.mailroom.sending.application.tracking.port.EmailLinkRepository;
import dev.ngb.mailroom.sending.application.tracking.port.TrackingTokens;
import dev.ngb.mailroom.sending.domain.tracking.EmailLink;
import dev.ngb.mailroom.sending.domain.tracking.TrackingAction;
import dev.ngb.mailroom.shared.EmailKind;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The rewriter is where a marketing email stops being the tenant's text and becomes a set of endpoints this service
 * owns. The cases are the ones a recipient would otherwise discover: an unsubscribe counted as a click, a link that
 * dies with the body, a query string mangled by HTML escaping, and a link that aborts the whole send.
 */
class MarketingTrackingTest {

    private static final TenantId TENANT = new TenantId(UUID.randomUUID());
    private static final UUID EMAIL = UUID.randomUUID();
    private static final String BASE = "https://mail.example.test/email";

    private final List<EmailLink> recorded = new ArrayList<>();
    private final MarketingTracking tracking = new MarketingTracking(
            new FakeTokens(),
            new TrackingSettings("k1", BASE, Map.of("k1", "secret")),
            new RecordingLinks(recorded),
            UUID::randomUUID,
            () -> Instant.parse("2026-01-01T00:00:00Z"));

    @Test
    void theUnsubscribeLinkIsLeftAloneSoItSurvivesBodyPurging() {
        String unsubscribe = tokensOf().unsubscribeUrl();
        String html = "<p><a href=\"" + unsubscribe + "\">Unsubscribe</a></p>";

        TrackingHook.Tracked tracked = tracking.prepare(
                new RenderedEmail(null, 0, "subject", html, null), EmailKind.MARKETING, EMAIL, TENANT);

        assertThat(tracked.html()).contains("href=\"" + unsubscribe + "\"");
        assertThat(tracked.links()).as("an unsubscribe is not a click target").isEmpty();
        assertThat(recorded).isEmpty();
    }

    @Test
    void anOrdinaryLinkIsRewrittenAndOfferedForRecording() {
        TrackingHook.Tracked tracked = tracking.prepare(
                new RenderedEmail(null, 0, "subject", "<a href=\"https://example.test/x\">x</a>", null),
                EmailKind.MARKETING,
                EMAIL,
                TENANT);

        assertThat(tracked.html()).contains(BASE + "/t/c/").doesNotContain("example.test/x");
        assertThat(tracked.links()).singleElement().satisfies(link -> {
            assertThat(link.position()).isZero();
            assertThat(link.url()).isEqualTo("https://example.test/x");
        });
        assertThat(tracked.headers()).containsEntry("List-Unsubscribe", "<" + tokensOf().unsubscribeUrl() + ">");

        // The send path records the proposed links; prepare itself writes nothing.
        tracked.links().forEach(link -> tracking.recordLink(link, EMAIL, TENANT, Instant.now()));
        assertThat(recorded).singleElement().extracting(EmailLink::url).isEqualTo("https://example.test/x");
    }

    @Test
    void anEscapedAmpersandIsRecordedAsTheRealQueryString() {
        // The renderer escapes an attribute value, so the body carries &amp; while the destination carries &.
        TrackingHook.Tracked tracked = tracking.prepare(
                new RenderedEmail(
                        null, 0, "subject", "<a href=\"https://example.test/p?a=1&amp;b=2\">x</a>", null),
                EmailKind.MARKETING,
                EMAIL,
                TENANT);

        assertThat(tracked.links()).singleElement()
                .extracting(TrackingHook.Link::url)
                .isEqualTo("https://example.test/p?a=1&b=2");
    }

    @Test
    void aLinkTheColumnCannotStoreIsLeftUntrackedInsteadOfAbortingTheSend() {
        String tooLong = "https://example.test/" + "a".repeat(4100);
        String html = "<a href=\"" + tooLong + "\">long</a><a href=\"https://example.test/ok\">ok</a>";

        TrackingHook.Tracked tracked = tracking.prepare(
                new RenderedEmail(null, 0, "subject", html, null), EmailKind.MARKETING, EMAIL, TENANT);

        assertThat(tracked.links()).singleElement()
                .satisfies(link -> assertThat(link.url()).isEqualTo("https://example.test/ok"));
        assertThat(tracked.links()).noneMatch(link -> link.url().length() > 4096);
    }

    @Test
    void aTransactionalEmailIsUntouched() {
        String html = "<a href=\"https://example.test/x\">x</a>";
        TrackingHook.Tracked tracked = tracking.prepare(
                new RenderedEmail(null, 0, "subject", html, null), EmailKind.TRANSACTIONAL, EMAIL, TENANT);

        assertThat(tracked.html()).isEqualTo(html);
        assertThat(tracked.headers()).isEmpty();
        assertThat(tracked.links()).isEmpty();
    }

    @Test
    void aTextOnlyMarketingEmailStillCarriesTheUnsubscribeHeaders() {
        TrackingHook.Tracked tracked = tracking.prepare(
                new RenderedEmail(null, 0, "subject", null, "plain text"), EmailKind.MARKETING, EMAIL, TENANT);

        assertThat(tracked.html()).isNull();
        assertThat(tracked.headers()).containsKeys("List-Unsubscribe", "List-Unsubscribe-Post");
    }

    private FakeTokens tokensOf() {
        return new FakeTokens();
    }

    /** Deterministic tokens: the test asserts the shape of the URL, not the signature. */
    private static final class FakeTokens implements TrackingTokens {

        @Override
        public String sign(TenantId tenantId, UUID emailId, int linkId, TrackingAction action) {
            return action.name().toLowerCase() + "-" + linkId;
        }

        @Override
        public Optional<Claims> verify(String token, TrackingAction expectedAction) {
            return Optional.empty();
        }

        String unsubscribeUrl() {
            return BASE + "/t/u/unsubscribe-0";
        }
    }

    private record RecordingLinks(List<EmailLink> sink) implements EmailLinkRepository {

        @Override
        public void insert(EmailLink link) {
            sink.add(link);
        }

        @Override
        public Optional<String> findUrl(UUID emailId, int position) {
            return Optional.empty();
        }

        @Override
        public int purgeForPurgedBodies(Instant bodyCutoff, int limit) {
            return 0;
        }
    }
}
