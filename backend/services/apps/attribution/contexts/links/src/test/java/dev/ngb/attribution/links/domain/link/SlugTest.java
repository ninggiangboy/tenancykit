package dev.ngb.attribution.links.domain.link;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.ngb.core.error.BadRequestException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** A slug is 3 to 64 lower-case characters, and a generated one is unambiguous and never a word. */
class SlugTest {

    @Test
    void acceptsAMinimalAndAMaximalSlug() {
        assertThat(Slug.of("abc").value()).isEqualTo("abc");
        assertThat(Slug.of("a".repeat(64)).value()).hasSize(64);
        assertThat(Slug.of("spring-sale_2026").value()).isEqualTo("spring-sale_2026");
    }

    @Test
    void lowerCasesACallerSuppliedSlug() {
        assertThat(Slug.of("  Spring-Sale  ").value()).isEqualTo("spring-sale");
    }

    @ParameterizedTest
    @ValueSource(strings = {"ab", "_abc", "-abc", "has space", "has/slash", "caf\u00e9"})
    void rejectsASlugOutsideTheRule(String value) {
        assertThatThrownBy(() -> Slug.of(value)).isInstanceOf(BadRequestException.class);
    }

    @Test
    void aSlugOneCharacterTooLongIsRejected() {
        assertThatThrownBy(() -> Slug.of("a".repeat(65))).isInstanceOf(BadRequestException.class);
    }

    @Test
    void aBlankSlugIsRejected() {
        assertThatThrownBy(() -> Slug.of("")).isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> Slug.of(null)).isInstanceOf(BadRequestException.class);
    }

    @Test
    void aGeneratedSlugIsTenUnambiguousCharacters() {
        for (int index = 0; index < 200; index++) {
            String generated = Slug.generate().value();
            assertThat(generated).hasSize(Slug.GENERATED_LENGTH);
            // Crockford base32 leaves out i, l, o and u, so a slug can never be misread as a word.
            assertThat(generated).doesNotContain("i", "l", "o", "u");
            assertThat(generated).matches("^[0-9a-z]+$");
        }
    }
}
