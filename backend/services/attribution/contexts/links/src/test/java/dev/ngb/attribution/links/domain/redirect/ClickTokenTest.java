package dev.ngb.attribution.links.domain.redirect;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.ngb.attribution.shared.redirect.ClickToken;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** The click token is the only thing that makes a click id trustworthy, so its failures must all be refusals. */
class ClickTokenTest {

    private static final Map<String, String> KEYS = Map.of("k1", "secret-one", "k2", "secret-two");

    @Test
    void aSignedTokenVerifiesBackToItsClickAndKey() {
        UUID clickId = UUID.randomUUID();
        Optional<ClickToken.Verified> verified = ClickToken.verify(ClickToken.sign(clickId, "k1", "secret-one"), KEYS);

        assertThat(verified).contains(new ClickToken.Verified(clickId, "k1"));
    }

    @Test
    void aTokenSignedWithAStillListedKeyVerifiesAfterRotation() {
        UUID clickId = UUID.randomUUID();
        // The active key is k2 now, but a link signed with k1 is still in the wild and must keep working.
        String old = ClickToken.sign(clickId, "k1", "secret-one");

        assertThat(ClickToken.verify(old, KEYS)).contains(new ClickToken.Verified(clickId, "k1"));
    }

    @Test
    void aTamperedClickIdIsRefused() {
        String token = ClickToken.sign(UUID.randomUUID(), "k1", "secret-one");
        String[] parts = token.split("~");
        String otherClick = java.util.Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(UUID.randomUUID().toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));

        assertThat(ClickToken.verify(otherClick + "~" + parts[1] + "~" + parts[2], KEYS)).isEmpty();
    }

    @Test
    void aTokenNamingAnUnknownKeyIsRefused() {
        String token = ClickToken.sign(UUID.randomUUID(), "k1", "secret-one");

        assertThat(ClickToken.verify(token, Map.of("k2", "secret-two"))).isEmpty();
    }

    @Test
    void malformedInputIsRefusedRatherThanThrowing() {
        assertThat(ClickToken.verify(null, KEYS)).isEmpty();
        assertThat(ClickToken.verify("", KEYS)).isEmpty();
        assertThat(ClickToken.verify("not-a-token", KEYS)).isEmpty();
        assertThat(ClickToken.verify("~~", KEYS)).isEmpty();
        assertThat(ClickToken.verify("a~b", KEYS)).isEmpty();
        assertThat(ClickToken.verify("\u0000~\u0000~\u0000", KEYS)).isEmpty();
        assertThat(ClickToken.verify("x".repeat(ClickToken.MAX_LENGTH + 1), KEYS)).isEmpty();
    }

    @Test
    void signingRefusesNothingThatCouldNotBeVerified() {
        assertThatThrownBy(() -> ClickToken.sign(null, "k1", "secret-one"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ClickToken.sign(UUID.randomUUID(), "bad key", "secret-one"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ClickToken.sign(UUID.randomUUID(), "k1", " "))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
