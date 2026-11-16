package dev.ngb.survey.collection.domain.link;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The link token's two properties: a link id can be read back from the URL at any time, and every reason a token does
 * not verify collapses into the same empty result.
 */
class LinkTokensTest {

    private static final LinkTokens.Keys KEYS = new LinkTokens.Keys("k1", Map.of("k1", "first-secret", "k0", "old-secret"));

    @Test
    void aSignedLinkIdReadsBack() {
        UUID linkId = UUID.randomUUID();

        Optional<UUID> read = LinkTokens.verify(LinkTokens.sign(linkId, KEYS), KEYS);

        assertThat(read).contains(linkId);
    }

    @Test
    void aTamperedTokenVerifiesToNothing() {
        String token = LinkTokens.sign(UUID.randomUUID(), KEYS);
        char[] characters = token.toCharArray();
        characters[characters.length - 2] = characters[characters.length - 2] == 'A' ? 'B' : 'A';

        assertThat(LinkTokens.verify(new String(characters), KEYS)).isEmpty();
    }

    @Test
    void aTokenFromARemovedKeyVerifiesToNothing() {
        LinkTokens.Keys old = new LinkTokens.Keys("k0", Map.of("k0", "old-secret"));
        String token = LinkTokens.sign(UUID.randomUUID(), old);

        assertThat(LinkTokens.verify(token, KEYS))
                .as("a key still configured keeps an old link working")
                .isPresent();
        assertThat(LinkTokens.verify(token, new LinkTokens.Keys("k1", Map.of("k1", "first-secret"))))
                .as("a removed key stops it")
                .isEmpty();
    }

    @Test
    void aBlankOrGarbageTokenVerifiesToNothing() {
        assertThat(LinkTokens.verify(null, KEYS)).isEmpty();
        assertThat(LinkTokens.verify("", KEYS)).isEmpty();
        assertThat(LinkTokens.verify("not base64 at all !!", KEYS)).isEmpty();
        assertThat(LinkTokens.verify("AAAA", KEYS)).isEmpty();
    }

    @Test
    void twoLinksNeverShareAToken() {
        assertThat(LinkTokens.sign(UUID.randomUUID(), KEYS))
                .isNotEqualTo(LinkTokens.sign(UUID.randomUUID(), KEYS));
    }
}
