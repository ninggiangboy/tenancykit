package dev.ngb.attribution.links.application.settings.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.ngb.core.error.BadRequestException;
import org.junit.jupiter.api.Test;

/** An origin accepted here is one a page may post from, so the refusals are the security property, not a nicety. */
class UpdateSettingsUseCaseTest {

    @Test
    void anHttpsOriginNormalizesToSchemeHostAndPort() {
        assertThat(UpdateSettingsUseCase.validOrigin("https://shop.example.com/")).isEqualTo("https://shop.example.com");
        assertThat(UpdateSettingsUseCase.validOrigin("https://Shop.Example.com:8443"))
                .isEqualTo("https://shop.example.com:8443");
    }

    @Test
    void aLocalhostHttpOriginIsAcceptedForDevelopment() {
        assertThat(UpdateSettingsUseCase.validOrigin("http://localhost:3000")).isEqualTo("http://localhost:3000");
    }

    @Test
    void aPlainHttpOriginOffLocalhostIsRefused() {
        assertThatThrownBy(() -> UpdateSettingsUseCase.validOrigin("http://shop.example.com"))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    void aWildcardOriginIsRefused() {
        assertThatThrownBy(() -> UpdateSettingsUseCase.validOrigin("https://*.example.com"))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    void anOriginWithAPathAQueryOrAFragmentIsRefused() {
        assertThatThrownBy(() -> UpdateSettingsUseCase.validOrigin("https://shop.example.com/cart"))
                .isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> UpdateSettingsUseCase.validOrigin("https://shop.example.com?x=1"))
                .isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> UpdateSettingsUseCase.validOrigin("https://shop.example.com#top"))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    void aBareHostIsRefused() {
        assertThatThrownBy(() -> UpdateSettingsUseCase.validOrigin("shop.example.com"))
                .isInstanceOf(BadRequestException.class);
    }
}
