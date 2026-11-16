package dev.ngb.attribution.links.domain.link;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.ngb.core.error.BadRequestException;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The destination checks are the only guard against turning the suite's domain into a phishing relay, so every rejected
 * class is a data row here rather than a claim in a comment.
 */
class DestinationUrlTest {

    @ParameterizedTest
    @ValueSource(strings = {
        "http://example.com/x",
        "https://example.com/x?utm_source=a",
        "https://shop.example.co.uk/checkout",
        "https://example.com:8443/path",
        "https://example.com/",
    })
    void acceptsAnAbsoluteHttpOrHttpsUrl(String url) {
        assertThat(new DestinationUrl(url).value()).isEqualTo(url);
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "ftp://example.com/x",
        "file:///etc/passwd",
        "javascript:alert(1)",
        "//example.com/x",
        "example.com/x",
        "https://user:pw@example.com/x",
        "https://localhost/x",
        "http://127.0.0.1/x",
        "http://10.1.2.3/x",
        "http://192.168.1.1/x",
        "http://169.254.169.254/latest/meta-data",
        "http://172.16.0.1/x",
        "http://[::1]/x",
        "https:///x",
    })
    void rejectsEveryUnsafeClass(String url) {
        assertThatThrownBy(() -> new DestinationUrl(url)).isInstanceOf(BadRequestException.class);
    }

    @Test
    void rejectsAUrlLongerThanTheLimit() {
        assertThatThrownBy(() -> new DestinationUrl("https://example.com/" + "a".repeat(DestinationUrl.MAX_LENGTH)))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    void refusesTheSuiteHostAndItsSubdomains() {
        List<String> forbidden = List.of("suite.example");
        assertThatThrownBy(() -> DestinationUrl.forSuite("https://suite.example/x", forbidden))
                .isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> DestinationUrl.forSuite("https://acme.suite.example/x", forbidden))
                .isInstanceOf(BadRequestException.class);
        assertThat(DestinationUrl.forSuite("https://example.com/x", forbidden).value())
                .isEqualTo("https://example.com/x");
    }

    @Test
    void detectsAClickParameterAlreadyOnTheDestination() {
        DestinationUrl url = new DestinationUrl("https://example.com/x?aid=stale&other=1");
        assertThat(url.queryHas("aid")).isTrue();
        assertThat(url.queryHas("other")).isTrue();
        assertThat(url.queryHas("utm_source")).isFalse();
    }

    @Test
    void reportsTheHostAndPathWithoutCredentials() {
        DestinationUrl url = new DestinationUrl("https://Example.COM:8443/Path?x=1");
        assertThat(url.host()).isEqualTo("example.com");
        assertThat(url.pathAndQuery()).isEqualTo("/Path?x=1");
    }
}
