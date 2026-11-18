package dev.ngb.attribution.links.domain.redirect;

import static org.assertj.core.api.Assertions.assertThat;

import dev.ngb.attribution.links.domain.link.Destination;
import dev.ngb.attribution.links.domain.link.DestinationUrl;
import dev.ngb.attribution.links.domain.link.DeviceMatch;
import dev.ngb.attribution.links.domain.link.Link;
import dev.ngb.attribution.links.domain.link.LinkKind;
import dev.ngb.attribution.links.domain.link.Slug;
import dev.ngb.core.id.TenantId;
import dev.ngb.core.visitor.DeviceClass;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

/** A weighted draw among the survivors of the match, and the fallback when none survives. */
class DestinationSelectorTest {

    private static final Instant NOW = Instant.parse("2026-03-10T12:00:00Z");

    @Test
    void overTenThousandClicksTheDrawStaysWithinThreePercentOfTheWeights() {
        Link link = link(List.of(
                destination("https://example.com/a", 1, DeviceMatch.any(), false),
                destination("https://example.com/b", 3, DeviceMatch.any(), true)));
        Random random = new Random(42);
        Map<String, Integer> counts = new HashMap<>();
        for (int index = 0; index < 10_000; index++) {
            Destination chosen = DestinationSelector.select(link, DeviceClass.DESKTOP, "US", random);
            counts.merge(chosen.url().value(), 1, Integer::sum);
        }
        // Weights 1:3 mean 25%/75%; a 10,000 draw lands well inside three percentage points of both.
        assertThat(counts.get("https://example.com/a") / 10_000.0).isBetween(0.22, 0.28);
        assertThat(counts.get("https://example.com/b") / 10_000.0).isBetween(0.72, 0.78);
    }

    @Test
    void aDestinationWhoseRuleFailsIsNotDrawn() {
        DeviceMatch mobileOnly = new DeviceMatch(List.of(DeviceClass.MOBILE), List.of());
        Link link = link(List.of(
                destination("https://example.com/mobile", 1, mobileOnly, false),
                destination("https://example.com/any", 1, DeviceMatch.any(), true)));
        Random random = new Random(7);

        List<String> chosen = IntStream.range(0, 200)
                .mapToObj(index -> DestinationSelector.select(link, DeviceClass.DESKTOP, "US", random).url().value())
                .distinct()
                .toList();

        assertThat(chosen).containsExactly("https://example.com/any");
    }

    @Test
    void theFallbackAnswersWhenNoRulePasses() {
        DeviceMatch mobileOnly = new DeviceMatch(List.of(DeviceClass.MOBILE), List.of());
        DeviceMatch otherCountry = new DeviceMatch(List.of(), List.of("VN"));
        Link link = link(List.of(
                destination("https://example.com/mobile", 1, mobileOnly, false),
                destination("https://example.com/vn", 1, otherCountry, false),
                destination("https://example.com/fallback", 1, new DeviceMatch(List.of(DeviceClass.TABLET), List.of()), true)));

        Destination chosen = DestinationSelector.select(link, DeviceClass.DESKTOP, "US", new Random(1));

        assertThat(chosen.fallback()).isTrue();
        assertThat(chosen.url().value()).isEqualTo("https://example.com/fallback");
    }

    private static Link link(List<Destination> destinations) {
        return new Link(
                UUID.randomUUID(), new TenantId(UUID.randomUUID()), null, Slug.of("spring-sale"), LinkKind.STANDARD,
                null, dev.ngb.attribution.links.domain.link.LinkStatus.ACTIVE, null, null, null, Map.of(),
                destinations, 1, NOW, NOW);
    }
    private static Destination destination(String url, int weight, DeviceMatch match, boolean fallback) {
        return new Destination(
                UUID.randomUUID(), new DestinationUrl(url), POSITIONS.incrementAndGet(), weight, match, fallback);
    }

    private static final java.util.concurrent.atomic.AtomicInteger POSITIONS =
            new java.util.concurrent.atomic.AtomicInteger();
}
