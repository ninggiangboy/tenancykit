package dev.ngb.core.visitor;

import com.maxmind.db.CHMCache;
import com.maxmind.db.Reader;
import java.io.IOException;
import java.net.InetAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * Reads the country of an address from a DB-IP Lite {@code .mmdb} file.
 *
 * <p>The address is used in memory and never returned or logged: the only value that leaves this class is a two-letter
 * country or nothing. A missing, unreadable or stale file is not fatal — {@link #available()} is false, every lookup is
 * empty and a health check can say why — because losing the country must not cost a redirect.
 *
 * <p>DB-IP Lite is licensed CC BY 4.0 and must be attributed; {@code docs/attribution.md} carries that. The file is
 * fetched by a script and git-ignored, never committed.
 */
public final class GeoCountryReader implements AutoCloseable {

    /** @param country an ISO 3166-1 alpha-2 code, or empty when the file or the address has none */
    public record Country(String code) {}

    private final Reader reader;
    private final String buildDate;

    private GeoCountryReader(Reader reader, String buildDate) {
        this.reader = reader;
        this.buildDate = buildDate;
    }

    /**
     * Open the database at a path.
     *
     * @param path the configured file, or null/blank when GeoIP is not configured
     * @return a reader, or one whose {@link #available()} is false
     */
    public static GeoCountryReader open(String path) {
        if (path == null || path.isBlank() || !Files.isReadable(Path.of(path))) {
            return new GeoCountryReader(null, null);
        }
        try {
            Reader reader = new Reader(Path.of(path).toFile(), new CHMCache());
            return new GeoCountryReader(reader, reader.getMetadata().buildTime().toString());
        } catch (IOException e) {
            return new GeoCountryReader(null, null);
        }
    }

    /** Whether a database was loaded; a health check reports this and the build date. */
    public boolean available() {
        return reader != null;
    }

    /** The database's build date, or empty when none was loaded. */
    public Optional<String> buildDate() {
        return Optional.ofNullable(buildDate);
    }

    /**
     * The country of one address.
     *
     * @param address the client address; it is resolved in memory and never leaves this method
     */
    public Optional<Country> country(InetAddress address) {
        if (reader == null || address == null) {
            return Optional.empty();
        }
        try {
            CountryResponse response = reader.get(address, CountryResponse.class);
            if (response == null || response.country == null || response.country.isoCode == null) {
                return Optional.empty();
            }
            String code = response.country.isoCode.toUpperCase();
            return code.length() == 2 ? Optional.of(new Country(code)) : Optional.empty();
        } catch (IOException | RuntimeException e) {
            // A corrupt record or an address the file has no entry for is not a redirect's problem.
            return Optional.empty();
        }
    }

    /** The age of the database in days, so a health check can flag a stale file; empty when none was loaded. */
    public OptionalLong ageInDays(Instant now) {
        if (buildDate == null) {
            return OptionalLong.empty();
        }
        try {
            Instant built = Instant.parse(buildDate.endsWith("Z") ? buildDate : buildDate + "Z");
            long days = java.time.Duration.between(built, now).toDays();
            return OptionalLong.of(Math.max(0, days));
        } catch (RuntimeException e) {
            return OptionalLong.empty();
        }
    }

    @Override
    public void close() {
        if (reader != null) {
            try {
                reader.close();
            } catch (IOException e) {
                // Nothing to do: the file is read-only and the process is ending.
            }
        }
    }

    /** The subset of the DB-IP record this reader keeps; the address and every other field are discarded. */
    public static final class CountryResponse {
        private CountryData country;

        public CountryData getCountry() {
            return country;
        }

        public void setCountry(CountryData value) {
            this.country = value;
        }
    }

    /** @see CountryResponse */
    public static final class CountryData {
        private String isoCode;

        public String getIsoCode() {
            return isoCode;
        }

        public void setIsoCode(String value) {
            this.isoCode = value;
        }
    }
}
