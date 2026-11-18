package dev.ngb.core.suite.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.ECDSASigner;
import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.gen.ECKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtException;

/**
 * The two-issuer decoder. Platform signs its suite tokens with ES256, and a decoder built without naming that
 * algorithm defaults to RS256 and refuses every Platform token; the first test proves the decoder accepts the
 * algorithm Platform actually uses, against a real JWKS served over HTTP.
 */
class SuiteJwtDecodersTest {

    private HttpServer jwks;
    private ECKey platformKey;

    @BeforeEach
    void servePlatformJwks() throws Exception {
        platformKey = new ECKeyGenerator(Curve.P_256).generate();
        String body = new JWKSet(platformKey.toPublicJWK()).toString();
        jwks = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        jwks.createContext("/jwks", exchange -> {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        jwks.start();
    }

    @AfterEach
    void stop() {
        jwks.stop(0);
    }

    @Test
    void verifiesAnEs256PlatformTokenAgainstItsJwks() throws Exception {
        SuiteJwtDecoders decoders = decoders();

        Jwt jwt = decoders.decode(signed(platformKey, "http://platform.test", "billing"));

        assertThat(jwt.getAudience()).contains("billing");
        assertThat(decoders.isPlatformIssuer(jwt.getClaimAsString("iss"))).isTrue();
    }

    @Test
    void rejectsAPlatformTokenMintedForAnotherAudience() throws Exception {
        SuiteJwtDecoders decoders = decoders();

        assertThatThrownBy(() -> decoders.decode(signed(platformKey, "http://platform.test", "platform")))
                .isInstanceOf(JwtException.class)
                .hasMessageContaining("billing");
    }

    @Test
    void rejectsAPlatformTokenSignedByAnotherKey() throws Exception {
        SuiteJwtDecoders decoders = decoders();

        assertThatThrownBy(() ->
                        decoders.decode(signed(new ECKeyGenerator(Curve.P_256).generate(), "http://platform.test", "billing")))
                .isInstanceOf(JwtException.class);
    }

    @Test
    void onlyThePlatformIssuerIsRecognised() {
        SuiteJwtDecoders decoders = decoders();

        assertThat(decoders.isPlatformIssuer("http://platform.test")).isTrue();
        assertThat(decoders.isPlatformIssuer("http://keycloak.test/realms/billing")).isFalse();
        assertThat(decoders.appCode()).isEqualTo("billing");
    }

    @Test
    void aMalformedTokenIsABadJwt() {
        SuiteJwtDecoders decoders = decoders();

        assertThatThrownBy(() -> decoders.decode("not-a-jwt")).isInstanceOf(JwtException.class);
    }

    @Test
    void issuerOfReadsTheClaimBeforeVerification() throws Exception {
        assertThat(SuiteJwtDecoders.issuerOf(signed(platformKey, "http://platform.test", "billing")))
                .isEqualTo("http://platform.test");
    }

    private SuiteJwtDecoders decoders() {
        return new SuiteJwtDecoders(
                new SuiteJwtDecoders.Issuer("http://platform.test", "http://127.0.0.1:" + jwks.getAddress().getPort() + "/jwks"),
                new SuiteJwtDecoders.Issuer("http://keycloak.test/realms/billing", "http://keycloak.test/certs"),
                "billing");
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
