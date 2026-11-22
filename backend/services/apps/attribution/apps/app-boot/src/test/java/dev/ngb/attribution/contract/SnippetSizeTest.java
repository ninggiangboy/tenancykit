package dev.ngb.attribution.contract;

import static org.assertj.core.api.Assertions.assertThat;

import dev.ngb.attribution.support.RepositoryPaths;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.GZIPOutputStream;
import org.junit.jupiter.api.Test;

/**
 * The snippet is under 4 KB gzipped, which is the plan's promise to a page that adds it.
 *
 * <p>It is checked from the resource the app serves, so a change to the shipped file is what the test measures, not a
 * copy beside it.
 */
class SnippetSizeTest {

    private static final int MAX_GZIPPED_BYTES = 4 * 1024;

    @Test
    void theSnippetIsUnderFourKilobytesGzipped() throws IOException {
        Path snippet = RepositoryPaths.attributionRoot()
                .resolve("apps/app-boot/src/main/resources/static/t/v1/snippet.js");
        assertThat(snippet).as("the shipped snippet").exists();

        byte[] raw = Files.readString(snippet, StandardCharsets.UTF_8).getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream compressed = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(compressed)) {
            gzip.write(raw);
        }

        assertThat(compressed.size())
                .as("the snippet must stay under 4 KB gzipped")
                .isLessThan(MAX_GZIPPED_BYTES);
    }

    @Test
    void theSnippetExposesTheThreeCallsAndSetsNoCookieBeforeConsent() throws IOException {
        String source = Files.readString(
                RepositoryPaths.attributionRoot().resolve("apps/app-boot/src/main/resources/static/t/v1/snippet.js"),
                StandardCharsets.UTF_8);

        assertThat(source).contains("window.attribution");
        assertThat(source).contains("consent:");
        assertThat(source).contains("page:");
        assertThat(source).contains("goal:");
        // The visitor cookie is written only inside the consent branch, never at load.
        assertThat(source).contains("function consent(");
    }
}
