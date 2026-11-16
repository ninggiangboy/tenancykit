package dev.ngb.crm.support;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.JWSVerificationKeySelector;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jwt.proc.ConfigurableJWTProcessor;
import com.nimbusds.jwt.proc.DefaultJWTProcessor;
import dev.ngb.core.suite.security.SuiteJwtDecoders;
import java.util.List;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

/**
 * A decoder wired to the in-memory test key instead of an HTTP JWKS, so a MockMvc test needs no running server. It
 * keeps the production rules: the crm audience and a valid time window must hold for every token.
 *
 * <p>Because it subclasses the production type, it is injected everywhere that type is expected.
 */
public final class TestJwtDecoders extends SuiteJwtDecoders {

    private static final String APP_CODE = "crm";

    private final JwtDecoder delegate;

    public TestJwtDecoders() {
        super(new Issuer(TestTokenFactory.PLATFORM_ISSUER, "http://unused"), null, APP_CODE);
        this.delegate = build();
    }

    @Override
    public Jwt decode(String token) {
        return delegate.decode(token);
    }

    @Override
    public boolean isPlatformIssuer(String issuer) {
        return TestTokenFactory.PLATFORM_ISSUER.equals(issuer);
    }

    @Override
    public String appCode() {
        return APP_CODE;
    }

    private static JwtDecoder build() {
        ConfigurableJWTProcessor<SecurityContext> processor = new DefaultJWTProcessor<>();
        JWKSource<SecurityContext> source = new ImmutableJWKSet<>(new JWKSet(List.of(TestTokenFactory.publicKey())));
        processor.setJWSKeySelector(new JWSVerificationKeySelector<>(JWSAlgorithm.ES256, source));
        NimbusJwtDecoder nimbus = new NimbusJwtDecoder(processor);
        nimbus.setJwtValidator(audience(APP_CODE));
        return nimbus;
    }

    private static OAuth2TokenValidator<Jwt> audience(String appCode) {
        return token -> {
            OAuth2TokenValidatorResult times = JwtValidators.createDefault().validate(token);
            if (times.hasErrors()) {
                return times;
            }
            return token.getAudience() != null && token.getAudience().contains(appCode)
                    ? OAuth2TokenValidatorResult.success()
                    : OAuth2TokenValidatorResult.failure(new OAuth2Error(
                            OAuth2ErrorCodes.INVALID_TOKEN, "the token is not for " + appCode, null));
        };
    }
}
