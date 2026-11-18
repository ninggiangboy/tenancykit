package dev.ngb.platform.boot.security;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.ECDSASigner;
import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.gen.ECKeyGenerator;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.JWSVerificationKeySelector;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.nimbusds.jwt.proc.ConfigurableJWTProcessor;
import com.nimbusds.jwt.proc.DefaultJWTProcessor;
import dev.ngb.core.id.TenantId;
import dev.ngb.core.id.TenantSystemId;
import dev.ngb.platform.integration.application.token.port.TokenIssuer;
import java.text.ParseException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

/**
 * Signs the suite's tokens. One ES256 key signs, its thumbprint is the {@code kid}, and the JWKS publishes the
 * public halves of the signing key and, during a rotation, the previous one.
 */
public class SuiteTokenIssuer implements TokenIssuer {

    /** A token outlives one request and no more. */
    public static final Duration LIFETIME = Duration.ofMinutes(10);

    private final String issuer;
    private final ECKey signing;
    private final List<JWK> published;
    private final String keyId;

    public SuiteTokenIssuer(String issuer, String signingKeyPem, String previousSigningKeyPem) {
        this.issuer = issuer;
        this.signing = signingKeyPem == null || signingKeyPem.isBlank() ? ephemeralKey() : parseKey(signingKeyPem);
        this.keyId = thumbprint(signing);
        List<JWK> keys = new ArrayList<>();
        keys.add(new ECKey.Builder(signing.toPublicJWK()).keyID(keyId).build());
        if (previousSigningKeyPem != null && !previousSigningKeyPem.isBlank()) {
            ECKey previous = parseKey(previousSigningKeyPem);
            keys.add(new ECKey.Builder(previous.toPublicJWK()).keyID(thumbprint(previous)).build());
        }
        this.published = List.copyOf(keys);
    }

    public String issuer() {
        return issuer;
    }

    @Override
    public Issued issueService(String clientId, Set<String> scopes, String audience) {
        return issue("service/" + clientId, scopes, audience, Map.of());
    }

    @Override
    public Issued issueTenantSystem(
            TenantSystemId systemId, TenantId tenantId, String tenantCode, Set<String> scopes, String audience) {
        return issue(
                "tenant-system/" + systemId,
                scopes,
                audience,
                Map.of(
                        "tenant_id", tenantId.value().toString(),
                        "tenant_code", tenantCode,
                        "tenant_system_id", systemId.value().toString()));
    }

    private Issued issue(String subject, Set<String> scopes, String audience, Map<String, Object> extraClaims) {
        Instant now = Instant.now();
        JWTClaimsSet.Builder claims = new JWTClaimsSet.Builder()
                .issuer(issuer)
                .subject(subject)
                .audience(audience)
                .issueTime(Date.from(now))
                .expirationTime(Date.from(now.plus(LIFETIME)))
                .jwtID(UUID.randomUUID().toString())
                .claim("scope", String.join(" ", scopes));
        extraClaims.forEach(claims::claim);
        SignedJWT jwt = new SignedJWT(
                new JWSHeader.Builder(JWSAlgorithm.ES256).keyID(keyId).type(JOSEObjectType.JWT).build(), claims.build());
        try {
            jwt.sign(new ECDSASigner(signing.toECPrivateKey()));
        } catch (JOSEException e) {
            throw new IllegalStateException("could not sign the suite token", e);
        }
        return new Issued(jwt.serialize(), LIFETIME.toSeconds());
    }

    /** The public keys as JWK JSON, exactly what {@code GET /.well-known/jwks.json} serves. */
    public Map<String, Object> jwks() {
        List<Map<String, Object>> keys = published.stream().map(JWK::toJSONObject).toList();
        return Map.of("keys", keys);
    }

    /** Verifies a token this issuer signed, accepting the published keys (current and, during rotation, previous). */
    public NimbusJwtDecoder decoder() {
        ConfigurableJWTProcessor<SecurityContext> processor = new DefaultJWTProcessor<>();
        JWKSource<SecurityContext> source = new ImmutableJWKSet<>(new JWKSet(published));
        processor.setJWSKeySelector(new JWSVerificationKeySelector<>(JWSAlgorithm.ES256, source));
        return new NimbusJwtDecoder(processor);
    }

    private static ECKey ephemeralKey() {
        try {
            return new ECKeyGenerator(Curve.P_256).generate();
        } catch (JOSEException e) {
            throw new IllegalStateException("could not generate a development signing key", e);
        }
    }

    private static ECKey parseKey(String pem) {
        try {
            JWK key = JWK.parseFromPEMEncodedObjects(pem);
            if (!(key instanceof ECKey ecKey) || !ecKey.isPrivate()) {
                throw new IllegalArgumentException("the signing key must be an EC private key in PEM form");
            }
            return ecKey;
        } catch (JOSEException e) {
            throw new IllegalArgumentException("could not read the signing key", e);
        }
    }

    private static String thumbprint(ECKey key) {
        try {
            return key.toPublicJWK().computeThumbprint().toString();
        } catch (JOSEException e) {
            throw new IllegalStateException("could not compute the signing key thumbprint", e);
        }
    }

    /** The issuer of a token, read before verification so the right key set can be chosen. */
    public static String issuerOf(String token) throws ParseException {
        return SignedJWT.parse(token).getJWTClaimsSet().getIssuer();
    }
}
