package dev.ngb.core.visitor;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.api.Test;

/**
 * A referrer becomes its registrable domain or nothing: no scheme, no path, no query.
 *
 * <p>The fixture is a list of real referrers with the domain each must produce, so the public-suffix behaviour
 * (example.co.uk, example.com.vn, a bare co.uk) is a data row rather than a claim in a comment.
 */
class ReferrerDomainTest {

    static List<Arguments> fixtures() throws IOException {
        return lines("referrers.tsv").stream()
                .filter(line -> !line.isBlank() && !line.startsWith("#"))
                .map(line -> line.split("\t", -1))
                .map(columns -> Arguments.of(columns[0], columns[1].isEmpty() ? null : columns[1]))
                .toList();
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @MethodSource("fixtures")
    void reducesEveryReferrerToItsRegistrableDomain(String referrer, String expected) {
        assertThat(ReferrerDomain.of(referrer)).isEqualTo(java.util.Optional.ofNullable(expected));
    }

    @Test
    void theFixtureListCoversACompoundSuffixAndANonAsciiDomain() throws IOException {
        List<Arguments> all = fixtures();
        assertThat(all).as("a fixture list of one case proves little").hasSizeGreaterThanOrEqualTo(12);
        assertThat(all.stream().map(arguments -> arguments.get()[1])).contains("bbc.co.uk", "example.com.vn", null);
    }

    @Test
    void anIpLiteralIsNotADomain() {
        assertThat(ReferrerDomain.of("http://192.168.1.1:8080/tracking")).isEmpty();
        assertThat(ReferrerDomain.of("http://[2001:db8::1]/x")).isEmpty();
    }

    @Test
    void aSingleLabelHostIsNotADomain() {
        assertThat(ReferrerDomain.of("http://localhost:3000/x")).isEmpty();
    }

    @Test
    void aBarePublicSuffixIsNotRegistrable() {
        assertThat(ReferrerDomain.registrableDomain("co.uk")).isEmpty();
        assertThat(ReferrerDomain.registrableDomain("com.vn")).isEmpty();
    }

    private static List<String> lines(String resource) throws IOException {
        try (InputStream stream = ReferrerDomainTest.class.getResourceAsStream("/" + resource)) {
            if (stream == null) {
                throw new IllegalStateException("fixture " + resource + " is missing");
            }
            try (BufferedReader reader =
                    new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                return reader.lines().toList();
            }
        }
    }
}
