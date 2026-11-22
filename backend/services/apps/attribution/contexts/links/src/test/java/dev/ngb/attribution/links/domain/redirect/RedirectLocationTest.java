package dev.ngb.attribution.links.domain.redirect;

import static org.assertj.core.api.Assertions.assertThat;

import dev.ngb.attribution.links.domain.link.DestinationUrl;
import java.net.URI;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The Location is what a landing page receives, so the merge, the click parameter and the fragment are pinned here. */
class RedirectLocationTest {

    private static final String TOKEN = "abc~k1~sig";

    @Test
    void theClickParameterIsAppendedAndTheUtmValuesMerged() {
        String location = RedirectLocation.build(
                new DestinationUrl("https://example.com/landing"),
                Map.of("utm_source", "newsletter", "utm_medium", "email"),
                "aid",
                TOKEN);

        assertThat(location).startsWith("https://example.com/landing?");
        assertThat(location).contains("utm_source=newsletter");
        assertThat(location).contains("utm_medium=email");
        assertThat(location).contains("aid=" + escape(TOKEN));
    }

    @Test
    void aLinkUtmValueWinsOverOneAlreadyOnTheDestination() {
        String location = RedirectLocation.build(
                new DestinationUrl("https://example.com/landing?utm_source=old&keep=me"),
                Map.of("utm_source", "new"),
                "aid",
                TOKEN);

        assertThat(location).contains("utm_source=new");
        assertThat(location).doesNotContain("utm_source=old");
        // A parameter that is not a UTM value is the landing page's and is kept.
        assertThat(location).contains("keep=me");
    }

    @Test
    void aBareUtmNameIsStoredUnderTheUtmPrefix() {
        String location = RedirectLocation.build(
                new DestinationUrl("https://example.com/landing"),
                Map.of("source", "newsletter", "campaign", "spring"),
                "aid",
                TOKEN);

        assertThat(location).contains("utm_source=newsletter");
        assertThat(location).contains("utm_campaign=spring");
    }

    @Test
    void theFragmentIsPreserved() {
        String location = RedirectLocation.build(
                new DestinationUrl("https://example.com/landing?x=1#section-2"),
                Map.of(),
                "aid",
                TOKEN);

        assertThat(location).endsWith("#section-2");
        // The query comes before the fragment, never after it.
        assertThat(location.indexOf('?')).isLessThan(location.indexOf('#'));
    }

    @Test
    void aTenantChosenClickParameterIsTheNameUsed() {
        String location = RedirectLocation.build(
                new DestinationUrl("https://example.com/landing"), Map.of(), "ref", TOKEN);

        assertThat(location).contains("ref=" + escape(TOKEN));
        assertThat(location).doesNotContain("aid=");
    }

    @Test
    void theAppendedValueIsQueryEncodedSoItCannotBreakTheUrl() {
        String location = RedirectLocation.build(
                new DestinationUrl("https://example.com/landing"), Map.of(), "aid", "a b&c=d");

        assertThat(URI.create(location).getRawQuery()).contains("aid=a%20b%26c%3Dd");
    }

    private static String escape(String token) {
        return java.net.URLEncoder.encode(token, java.nio.charset.StandardCharsets.UTF_8).replace("+", "%20");
    }
}
