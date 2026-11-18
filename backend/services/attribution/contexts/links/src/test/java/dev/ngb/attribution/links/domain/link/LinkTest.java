package dev.ngb.attribution.links.domain.link;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.ngb.core.error.BadRequestException;
import dev.ngb.core.id.TenantId;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Exactly one fallback, a distinct position per destination, and a password that is never returned. */
class LinkTest {

    private static final Instant NOW = Instant.parse("2026-03-10T12:00:00Z");
    private final TenantId tenantId = new TenantId(UUID.randomUUID());

    @Test
    void aLinkNeedsExactlyOneFallback() {
        assertThatThrownBy(() -> create(List.of(destination(0, false), destination(1, false))))
                .isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> create(List.of(destination(0, true), destination(1, true))))
                .isInstanceOf(BadRequestException.class);
        assertThat(create(List.of(destination(0, true), destination(1, false))).fallback().position()).isZero();
    }

    @Test
    void twoDestinationsCannotShareAPosition() {
        assertThatThrownBy(() -> create(List.of(destination(0, true), destination(0, false))))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    void anEmptyDestinationListIsRefused() {
        assertThatThrownBy(() -> create(List.of())).isInstanceOf(BadRequestException.class);
    }

    @Test
    void moreThanTenDestinationsAreRefused() {
        List<Destination> many = java.util.stream.IntStream.range(0, 11)
                .mapToObj(index -> destination(index, index == 0))
                .toList();
        assertThatThrownBy(() -> create(many)).isInstanceOf(BadRequestException.class);
    }

    @Test
    void anExpiryInThePastIsRefusedOnCreate() {
        assertThatThrownBy(() -> Link.create(
                        UUID.randomUUID(), tenantId, null, Slug.of("spring-sale"), LinkKind.STANDARD, null,
                        NOW.minusSeconds(60), null, null, Map.of(), List.of(destination(0, true)), NOW))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    void aClickCapBelowOneIsRefused() {
        assertThatThrownBy(() -> Link.create(
                        UUID.randomUUID(), tenantId, null, Slug.of("spring-sale"), LinkKind.STANDARD, null,
                        null, 0L, null, Map.of(), List.of(destination(0, true)), NOW))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    void hasPasswordReflectsTheStoredHash() {
        Link plain = create(List.of(destination(0, true)));
        assertThat(plain.hasPassword()).isFalse();
        Link guarded = Link.create(
                UUID.randomUUID(), tenantId, null, Slug.of("spring-sale"), LinkKind.STANDARD, null, null, null,
                "$2a$10$hash", Map.of(), List.of(destination(0, true)), NOW);
        assertThat(guarded.hasPassword()).isTrue();
    }

    private Link create(List<Destination> destinations) {
        return Link.create(
                UUID.randomUUID(), tenantId, null, Slug.of("spring-sale"), LinkKind.STANDARD, null, null, null,
                null, Map.of("utm_source", "newsletter"), destinations, NOW);
    }

    private static Destination destination(int position, boolean fallback) {
        return new Destination(
                UUID.randomUUID(),
                new DestinationUrl("https://example.com/" + position),
                position,
                1,
                DeviceMatch.any(),
                fallback);
    }
}
