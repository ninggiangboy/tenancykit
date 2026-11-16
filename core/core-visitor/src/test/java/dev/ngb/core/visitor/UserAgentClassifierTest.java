package dev.ngb.core.visitor;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The classifier against a fixture list of real user agents.
 *
 * <p>The fixture is data in this module, one tab-separated line per agent: the agent, the device class, the browser
 * family and the traffic class. Adding a case is a line, and a rule that ages shows up as a red row rather than a
 * silent mis-count. The list carries the agents a marketing link actually meets, including the preview fetchers that
 * hit a redirect and must still receive their answer.
 */
class UserAgentClassifierTest {

    static Stream<Arguments> fixtures() throws IOException {
        return lines("user-agents.tsv").stream()
                .filter(line -> !line.isBlank() && !line.startsWith("#"))
                .map(line -> line.split("\t", -1))
                .map(columns -> Arguments.of(columns[0], DeviceClass.valueOf(columns[1]), columns[2],
                        TrafficClass.valueOf(columns[3])));
    }

    @ParameterizedTest(name = "{1} / {2} / {3}")
    @MethodSource("fixtures")
    void classifiesEveryFixtureAsExpected(String agent, DeviceClass device, String browser, TrafficClass traffic) {
        UserAgentClassifier.Classification result = UserAgentClassifier.classify(agent);
        assertThat(result.device()).isEqualTo(device);
        assertThat(result.browserFamily()).isEqualTo(browser);
        assertThat(result.traffic()).isEqualTo(traffic);
    }

    @ParameterizedTest(name = "empty agent is a bot")
    @org.junit.jupiter.params.provider.ValueSource(strings = {"", "   "})
    void anEmptyUserAgentIsABot(String agent) {
        assertThat(UserAgentClassifier.classify(agent).traffic()).isEqualTo(TrafficClass.BOT);
        assertThat(UserAgentClassifier.classify(agent).device()).isEqualTo(DeviceClass.UNKNOWN);
    }

    @org.junit.jupiter.api.Test
    void theFixtureListCoversEveryDeviceAndTrafficClass() throws IOException {
        List<Arguments> all = fixtures().toList();
        assertThat(all).as("a fixture list that tests one class proves little").hasSizeGreaterThanOrEqualTo(40);
        assertThat(all.stream().map(arguments -> arguments.get()[2]).distinct().toList())
                .as("the fixture list names a browser")
                .isNotEmpty();
        assertThat(all.stream().map(arguments -> arguments.get()[1]).distinct().toList())
                .as("every device class appears at least once")
                .contains(DeviceClass.DESKTOP, DeviceClass.MOBILE, DeviceClass.TABLET);
        assertThat(all.stream().map(arguments -> arguments.get()[3]).distinct().toList())
                .as("both a person and a machine appear")
                .contains(TrafficClass.HUMAN, TrafficClass.BOT);
    }

    /** {@code BrowserFamily} and {@code TrafficClass} accessors, so a caller never spells a magic string. */
    @org.junit.jupiter.api.Test
    void aBotTokenIsMatchedCaseInsensitively() {
        assertThat(UserAgentClassifier.trafficOf("Slackbot-LinkExpanding 1.0 (+https://api.slack.com/robots)"))
                .isEqualTo(TrafficClass.BOT);
        assertThat(UserAgentClassifier.trafficOf("FACEBOOKEXTERNALHIT/1.1"))
                .isEqualTo(TrafficClass.BOT);
    }

    private static List<String> lines(String resource) throws IOException {
        try (InputStream stream = UserAgentClassifierTest.class.getResourceAsStream("/" + resource)) {
            if (stream == null) {
                throw new IllegalStateException("fixture " + resource + " is missing");
            }
            try (BufferedReader reader =
                    new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                return reader.lines().toList();
            }
        } catch (UncheckedIOException e) {
            throw e.getCause();
        }
    }
}
