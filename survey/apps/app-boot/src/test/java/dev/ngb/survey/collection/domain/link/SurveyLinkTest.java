package dev.ngb.survey.collection.domain.link;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.ngb.core.id.TenantId;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** A link's lifecycle in the domain: it opens while active and unexpired, and never again once revoked. */
class SurveyLinkTest {

    private static final TenantId TENANT = new TenantId(UUID.randomUUID());
    private static final UUID SURVEY = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-11-16T00:00:00Z");

    @Test
    void aPublicLinkOpensWhileActive() {
        SurveyLink link = SurveyLink.publicLink(UUID.randomUUID(), TENANT, SURVEY, "newsletter", NOW);

        assertThat(link.opensAt(NOW)).isTrue();
        assertThat(link.personal()).isFalse();
    }

    @Test
    void anExpiredPersonalLinkNoLongerOpens() {
        SurveyLink link = SurveyLink.personalLink(
                UUID.randomUUID(), TENANT, SURVEY, "mail", "ref-1", NOW.plusSeconds(60), NOW);

        assertThat(link.opensAt(NOW)).isTrue();
        assertThat(link.opensAt(NOW.plusSeconds(61))).isFalse();
    }

    @Test
    void aRevokedLinkNeverOpensAgain() {
        SurveyLink link = SurveyLink.publicLink(UUID.randomUUID(), TENANT, SURVEY, "qr", NOW)
                .revoked();

        assertThat(link.opensAt(NOW)).isFalse();
        assertThat(link.status()).isEqualTo(LinkStatus.REVOKED);
    }

    @Test
    void onlyAPersonalLinkCarriesARecipient() {
        assertThatThrownBy(() -> new SurveyLink(
                        UUID.randomUUID(), TENANT, SURVEY, LinkKind.PUBLIC, "newsletter", "ref-1", null,
                        LinkStatus.ACTIVE, NOW))
                .as("a public link names only its source")
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SurveyLink(
                        UUID.randomUUID(), TENANT, SURVEY, LinkKind.PERSONAL, "mail", null, null,
                        LinkStatus.ACTIVE, NOW))
                .as("a personal link must name its recipient")
                .isInstanceOf(IllegalArgumentException.class);
    }
}
