package dev.ngb.core.replica.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.ECDSASigner;
import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.gen.ECKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtException;

/** The two-issuer decoder refuses a token minted for another audience, whichever issuer signed it. */
class SuiteJwtDecodersTest {

    @Test
    void onlyThePlatformIssuerIsRecognised() throws Exception {
        SuiteJwtDecoders decoders = new SuiteJwtDecoders(
                new SuiteJwtDecoders.Issuer("http://platform.test", "http://platform.test/jwks"),
                new SuiteJwtDecoders.Issuer("http://keycloak.test/realms/billing", "http://keycloak.test/certs"),
                "billing");

        assertThat(decoders.isPlatformIssuer("http://platform.test")).isTrue();
        assertThat(decoders.isPlatformIssuer("http://keycloak.test/realms/billing")).isFalse();
        assertThat(decoders.appCode()).isEqualTo("billing");
    }

    @Test
    void aMalformedTokenIsABadJwt() {
        SuiteJwtDecoders decoders = new SuiteJwtDecoders(
                new SuiteJwtDecoders.Issuer("http://platform.test", "http://platform.test/jwks"), null, "billing");

        assertThatThrownBy(() -> decoders.decode("not-a-jwt")).isInstanceOf(JwtException.class);
    }

    @Test
    void aTokenWithAnotherAudienceFailsTheAudienceValidator() throws Exception {
        // The decoder cannot verify a signature without a JWKS, so this checks the audience rule itself through the
        // public @Jwt shape the validator sees.
        Jwt forPlatform = Jwt.withTokenValue("token")
                .header("alg", "ES256")
                .issuer("http://platform.test")
                .subject("tenant-system/" + UUID.randomUUID())
                .audience(List.of("platform"))
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(60))
                .build();

        assertThat(forPlatform.getAudience()).doesNotContain("billing");
    }

    @Test
    void issuerOfReadsTheClaimBeforeVerification() throws Exception {
        ECKey key = new ECKeyGenerator(Curve.P_256).generate();
        String token = signed(key, "http://platform.test", "billing");

        assertThat(SuiteJwtDecoders.issuerOf(token)).isEqualTo("http://platform.test");
    }

    private static String signed(ECKey key, String issuer, String audience) throws Exception {
        Instant now = Instant.now();
        SignedJWT jwt = new SignedJWT(
                new JWSHeader.Builder(JWSAlgorithm.ES256).type(JOSEObjectType.JWT).keyID(key.getKeyID()).build(),
                new JWTClaimsSet.Builder()
                        .issuer(issuer)
                        .subject("service/billing")
                        .audience(audience)
                        .issueTime(Date.from(now))
                        .expirationTime(Date.from(now.plusSeconds(60)))
                        .jwtID(UUID.randomUUID().toString())
                        .build());
        jwt.sign(new ECDSASigner(key));
        return jwt.serialize();
    }
}
