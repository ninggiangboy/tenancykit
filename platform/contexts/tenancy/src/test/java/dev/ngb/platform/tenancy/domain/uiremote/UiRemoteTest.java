package dev.ngb.platform.tenancy.domain.uiremote;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.Set;
import org.junit.jupiter.api.Test;

class UiRemoteTest {

    private static final Instant NOW = Instant.parse("2026-11-17T10:00:00Z");

    @Test
    void aRegisteredRemoteIsDisabledAndHasNoActiveVersion() {
        UiRemote remote = UiRemote.register("billing", "billing", "Billing", Set.of(RemoteAudience.TENANT), 20, NOW);

        assertThat(remote.isEnabled()).isFalse();
        assertThat(remote.activeVersion()).isNull();
        assertThat(remote.version()).isEqualTo(1);
    }

    @Test
    void enablingWithoutAnActiveVersionIsRejected() {
        UiRemote remote = UiRemote.register("billing", "billing", "Billing", Set.of(RemoteAudience.TENANT), 20, NOW);

        assertThatThrownBy(() -> remote.update(true, null, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("active version");
    }

    @Test
    void activatingAndEnablingInOneCallIsTheOperatorsClick() {
        UiRemote remote = UiRemote.register("billing", "billing", "Billing", Set.of(RemoteAudience.TENANT), 20, NOW);

        assertThat(remote.update(true, "1.0.0+abc1234", null, null)).isTrue();
        assertThat(remote.isEnabled()).isTrue();
        assertThat(remote.activeVersion()).isEqualTo("1.0.0+abc1234");
        assertThat(remote.version()).isEqualTo(2);
    }

    @Test
    void aNoOpReportsNoChangeAndKeepsTheVersion() {
        UiRemote remote = UiRemote.register("billing", "billing", "Billing", Set.of(RemoteAudience.TENANT), 20, NOW);

        assertThat(remote.update(false, null, "Billing", 20)).isFalse();
        assertThat(remote.version()).isEqualTo(1);
    }

    @Test
    void aRemoteIsMountedInAtLeastOneAudience() {
        assertThatThrownBy(() -> UiRemote.register("billing", "billing", "Billing", Set.of(), 20, NOW))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aNameIsLowerCaseAndBounded() {
        assertThatThrownBy(() -> UiRemote.register("Billing", "billing", "Billing", Set.of(RemoteAudience.TENANT), 20, NOW))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> UiRemote.register("a", "billing", "Billing", Set.of(RemoteAudience.TENANT), 20, NOW))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
