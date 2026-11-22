package dev.ngb.notification.dispatch;

import static org.assertj.core.api.Assertions.assertThat;

import dev.ngb.notification.dispatch.domain.template.LocalePolicy;
import org.junit.jupiter.api.Test;

class LocalePolicyTest {

    @Test
    void theUserWinsThenTheTenantThenEnglish() {
        assertThat(LocalePolicy.choose("vi", "en")).isEqualTo("vi");
        assertThat(LocalePolicy.choose(null, "vi")).isEqualTo("vi");
        assertThat(LocalePolicy.choose(null, null)).isEqualTo("en");
        assertThat(LocalePolicy.choose("", "")).isEqualTo("en");
    }

    /** A bad preference must never block a notification, so an unsupported tag falls through. */
    @Test
    void anUnsupportedTagFallsThrough() {
        assertThat(LocalePolicy.choose("fr", "vi")).isEqualTo("vi");
        assertThat(LocalePolicy.choose("fr-FR", "de")).isEqualTo("en");
    }

    @Test
    void aRegionTagReducesToItsLanguage() {
        assertThat(LocalePolicy.choose("vi-VN", null)).isEqualTo("vi");
    }
}
