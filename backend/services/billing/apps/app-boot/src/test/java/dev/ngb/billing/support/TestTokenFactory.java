package dev.ngb.billing.support;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.ECDSASigner;
import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.gen.ECKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * One ES256 key pair for the whole test JVM. Billing's tests mint Keycloak-shaped user tokens and Platform-shaped
 * Tenant System tokens with it, and a local {@code /.well-known/jwks.json} publishes the public half, so no real
 * Keycloak or Platform is needed to test Billing's own security rules.
 */
public final class TestTokenFactory {

    public static final String PLATFORM_ISSUER = "http://platform.test";
    public static final String USER_ISSUER = "http://keycloak.test/realms/tenancykit";

    private static final ECKey KEY = generate();
    private static final String KEY_ID = thumbprint();

    private TestTokenFactory() {}

    /** The public keys as JWK JSON, exactly what the decoder reads. */
    public static Map<String, Object> jwks() {
        return Map.of("keys", List.of(publicKey()).stream().map(JWK::toJSONObject).toList());
    }

    /** The public half of the signing key, for a decoder built without an HTTP JWKS. */
    public static JWK publicKey() {
        return new ECKey.Builder(KEY.toPublicJWK()).keyID(KEY_ID).build();
    }

    /** A Keycloak user token: only the subject matters to the replica actor resolver. */
    public static String userToken(String subject) {
        return sign(USER_ISSUER, subject, "billing", Map.of());
    }

    /** A Platform Tenant System token: the subject names the system, the claims name its tenant. */
    public static String tenantSystemToken(UUID systemId, UUID tenantId, String... scopes) {
        return sign(PLATFORM_ISSUER, "tenant-system/" + systemId, "billing", Map.of(
                "tenant_id", tenantId.toString(),
                "scope", String.join(" ", scopes)));
    }

    /** A token whose audience is another app, to prove Billing refuses it. */
    public static String tokenForOtherAudience(String subject) {
        return sign(USER_ISSUER, subject, "platform", Map.of());
    }

    private static String sign(String issuer, String subject, String audience, Map<String, Object> extra) {
        Instant now = Instant.now();
        JWTClaimsSet.Builder claims = new JWTClaimsSet.Builder()
                .issuer(issuer)
                .subject(subject)
                .audience(audience)
                .issueTime(Date.from(now))
                .expirationTime(Date.from(now.plusSeconds(600)))
                .jwtID(UUID.randomUUID().toString());
        extra.forEach(claims::claim);
        SignedJWT jwt = new SignedJWT(
                new JWSHeader.Builder(JWSAlgorithm.ES256).keyID(KEY_ID).type(JOSEObjectType.JWT).build(), claims.build());
        try {
            jwt.sign(new ECDSASigner(KEY.toECPrivateKey()));
        } catch (JOSEException e) {
            throw new IllegalStateException("could not sign a test token", e);
        }
        return jwt.serialize();
    }

    private static ECKey generate() {
        try {
            return new ECKeyGenerator(Curve.P_256).generate();
        } catch (JOSEException e) {
            throw new IllegalStateException("could not generate the test signing key", e);
        }
    }

    private static String thumbprint() {
        try {
            return KEY.toPublicJWK().computeThumbprint().toString();
        } catch (JOSEException e) {
            throw new IllegalStateException("could not compute the test key thumbprint", e);
        }
    }

    /** Serves the public keys at the path the decoder is pointed at. */
    @RestController
    public static class JwksEndpoint {

        @GetMapping(path = "/test-jwks", produces = MediaType.APPLICATION_JSON_VALUE)
        Map<String, Object> jwks() {
            return TestTokenFactory.jwks();
        }
    }
}
