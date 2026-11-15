package dev.ngb.mailroom.sending.adapter.tracking.crypto;

import static org.assertj.core.api.Assertions.assertThat;

import dev.ngb.core.id.TenantId;
import dev.ngb.mailroom.sending.application.tracking.service.TrackingSettings;
import dev.ngb.mailroom.sending.domain.tracking.TrackingAction;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** A token authorises exactly one action on one email, and a rotated key still verifies the mail already sent. */
class HmacTrackingTokensTest {

    private static final TrackingSettings SETTINGS =
            new TrackingSettings("k2", "https://mail.example.com/email", Map.of("k1", "old-secret", "k2", "new-secret"));

    private final HmacTrackingTokens tokens = new HmacTrackingTokens(SETTINGS);
    private final TenantId tenant = new TenantId(UUID.randomUUID());
    private final UUID emailId = UUID.randomUUID();

    @Test
    void aSignedTokenVerifiesWithItsClaims() {
        String token = tokens.sign(tenant, emailId, 7, TrackingAction.CLICK);
        assertThat(tokens.verify(token, TrackingAction.CLICK))
                .hasValueSatisfying(claims -> {
                    assertThat(claims.tenantId()).isEqualTo(tenant);
                    assertThat(claims.emailId()).isEqualTo(emailId);
                    assertThat(claims.linkId()).isEqualTo(7);
                });
    }

    @Test
    void aTamperedTokenVerifiesNothing() {
        String token = tokens.sign(tenant, emailId, 0, TrackingAction.OPEN);
        byte[] decoded = java.util.Base64.getUrlDecoder().decode(token);
        // Flip a bit inside the MAC, which is the last 32 bytes of the decoded token.
        decoded[decoded.length - 1] ^= 0x01;
        String tampered = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(decoded);
        assertThat(tokens.verify(tampered, TrackingAction.OPEN)).isEmpty();
    }

    @Test
    void theWrongActionIsRefused() {
        String token = tokens.sign(tenant, emailId, 0, TrackingAction.OPEN);
        assertThat(tokens.verify(token, TrackingAction.UNSUBSCRIBE)).isEmpty();
    }

    @Test
    void aTruncatedOrGarbageTokenIsEmptyNotAnError() {
        assertThat(tokens.verify("", TrackingAction.OPEN)).isEmpty();
        assertThat(tokens.verify("not-a-token", TrackingAction.OPEN)).isEmpty();
        assertThat(tokens.verify("AAAA", TrackingAction.OPEN)).isEmpty();
    }

    @Test
    void aTokenFromThePreviousKeyStillVerifiesAfterRotation() {
        TrackingSettings before = new TrackingSettings("k1", "https://mail.example.com/email", Map.of("k1", "old-secret"));
        String token = new HmacTrackingTokens(before).sign(tenant, emailId, 2, TrackingAction.CLICK);
        assertThat(tokens.verify(token, TrackingAction.CLICK)).isPresent();
    }
}
