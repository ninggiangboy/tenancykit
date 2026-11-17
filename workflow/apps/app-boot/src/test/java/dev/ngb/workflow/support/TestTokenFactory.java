package dev.ngb.workflow.support;

import com.nimbusds.jose.JOSEException;
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
import java.util.Map;
import java.util.UUID;

/**
 * One ES256 key pair for the whole test JVM. workflow's tests mint Keycloak-shaped user tokens and Platform-shaped Tenant
 * System tokens with it, so no real Keycloak or Platform is needed to test workflow's own security rules.
 */
public final class TestTokenFactory {

    public static final String PLATFORM_ISSUER = "http://platform.test";
    public static final String USER_ISSUER = "http://keycloak.test/realms/billing";

    private static final ECKey KEY = generate();
    private static final String KEY_ID = thumbprint();

    private TestTokenFactory() {}

    /** The public half of the signing key, for a decoder built without an HTTP JWKS. */
    public static ECKey publicKey() {
        return new ECKey.Builder(KEY.toPublicJWK()).keyID(KEY_ID).build();
    }

    /** A Keycloak user token: only the subject matters to the replica actor resolver. */
    public static String userToken(String subject) {
        return sign(USER_ISSUER, subject, Map.of());
    }

    /** A Platform Tenant System token: the subject names the system, the claims name its tenant and its codes. */
    public static String tenantSystemToken(UUID systemId, UUID tenantId, String... scopes) {
        return sign(PLATFORM_ISSUER, "tenant-system/" + systemId, Map.of(
                "tenant_id", tenantId.toString(),
                "scope", String.join(" ", scopes)));
    }

    /** A token whose audience is another app, to prove workflow refuses it. */
    public static String tokenForOtherAudience(String subject) {
        return sign(USER_ISSUER, subject, "platform", Map.of());
    }

    private static String sign(String issuer, String subject, Map<String, Object> extra) {
        return sign(issuer, subject, "workflow", extra);
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
}
