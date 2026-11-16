package dev.ngb.core.visitor;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.InetAddress;
import org.junit.jupiter.api.Test;

/**
 * A missing database is not fatal: {@link GeoCountryReader#open} on a blank or absent path gives a reader whose
 * {@link GeoCountryReader#available()} is false, every lookup is empty, and a health check can say why.
 *
 * <p>The committed tests never depend on a downloaded {@code .mmdb}; the real file is fetched by a script and exercised
 * by the deployment's own check, not by this build.
 */
class GeoCountryReaderTest {

    @Test
    void aMissingDatabaseIsUnavailableAndAnswersNothing() throws IOException {
        try (GeoCountryReader reader = GeoCountryReader.open("/nonexistent/dbip-country-lite.mmdb")) {
            assertThat(reader.available()).isFalse();
            assertThat(reader.country(InetAddress.getByName("203.0.113.7"))).isEmpty();
            assertThat(reader.buildDate()).isEmpty();
        }
    }

    @Test
    void aBlankPathIsUnavailable() {
        try (GeoCountryReader reader = GeoCountryReader.open("")) {
            assertThat(reader.available()).isFalse();
        }
        try (GeoCountryReader reader = GeoCountryReader.open(null)) {
            assertThat(reader.available()).isFalse();
        }
    }

    @Test
    void aDirectoryPathIsUnavailableRatherThanFatal() {
        try (GeoCountryReader reader = GeoCountryReader.open(System.getProperty("java.io.tmpdir"))) {
            assertThat(reader.available()).isFalse();
        }
    }

    @Test
    void anUnavailableReaderAnswersNoCountryForAnyAddress() throws IOException {
        try (GeoCountryReader reader = GeoCountryReader.open(null)) {
            assertThat(reader.country(InetAddress.getByName("8.8.8.8"))).isEmpty();
            assertThat(reader.country(null)).isEmpty();
            assertThat(reader.ageInDays(java.time.Instant.now())).isEmpty();
        }
    }

    /** No test or log line prints an address: the class returns a country or nothing, so this asserts the surface. */
    @Test
    void theOnlyValueThatLeavesTheReaderIsACountryCode() throws IOException {
        try (GeoCountryReader reader = GeoCountryReader.open(null)) {
            var result = reader.country(InetAddress.getByName("203.0.113.7"));
            assertThat(result).isEmpty();
        }
    }
}
