package dev.ngb.platform.tenancy.domain.uiremote;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class UiRemoteVersionTest {

    private static final Instant NOW = Instant.parse("2026-11-17T10:00:00Z");
    private static final String INTEGRITY = "sha384-" + "A".repeat(64);
    private static final String ENTRY = "/console/remotes/billing/1.0.0+abc1234/mf-manifest.json";

    @Test
    void registersAVersionInsideItsOwnPublishedDirectory() {
        UiRemoteVersion version = UiRemoteVersion.register(
                "billing", "1.0.0+abc1234", ENTRY, INTEGRITY, 1, NOW, "service/frontend-ci");

        assertThat(version.entryPath()).isEqualTo(ENTRY);
        assertThat(version.registeredBy()).isEqualTo("service/frontend-ci");
    }

    @Test
    void refusesAnEntryPathOutsideTheRemoteVersionDirectory() {
        assertThatThrownBy(() -> UiRemoteVersion.register(
                "billing", "1.0.0+abc1234", "/console/remotes/billing/2.0.0/mf-manifest.json", INTEGRITY, 1, NOW, "ci"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("/console/remotes/billing/1.0.0+abc1234/");
        assertThatThrownBy(() -> UiRemoteVersion.register(
                "billing", "1.0.0+abc1234", "https://elsewhere.test/mf-manifest.json", INTEGRITY, 1, NOW, "ci"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> UiRemoteVersion.register(
                "billing", "1.0.0+abc1234", "/console/remotes/billing/1.0.0+abc1234/nested/mf-manifest.json", INTEGRITY, 1, NOW, "ci"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> UiRemoteVersion.register(
                "billing", "1.0.0+abc1234", "/console/remotes/billing/1.0.0+abc1234/..", INTEGRITY, 1, NOW, "ci"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void refusesAnIntegrityThatIsNotAQuarterMegabyteBase64Digest() {
        assertThatThrownBy(() -> UiRemoteVersion.register("billing", "1.0.0", ENTRY, "sha512-abc", 1, NOW, "ci"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> UiRemoteVersion.register("billing", "1.0.0", ENTRY, INTEGRITY.replace("sha384-", ""), 1, NOW, "ci"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void refusesAMajorBelowOne() {
        assertThatThrownBy(() -> UiRemoteVersion.register("billing", "1.0.0", ENTRY, INTEGRITY, 0, NOW, "ci"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void theSameContentIsTheIdempotentCaseAndDifferentContentIsNot() {
        UiRemoteVersion first = UiRemoteVersion.register("billing", "1.0.0+abc1234", ENTRY, INTEGRITY, 1, NOW, "ci");
        UiRemoteVersion again =
                UiRemoteVersion.restore("billing", "1.0.0+abc1234", ENTRY, INTEGRITY, 1, NOW, "other");
        UiRemoteVersion changed =
                UiRemoteVersion.restore("billing", "1.0.0+abc1234", ENTRY, "sha384-" + "B".repeat(64), 1, NOW, "ci");

        assertThat(first.sameContent(again)).isTrue();
        assertThat(first.sameContent(changed)).isFalse();
    }
}
