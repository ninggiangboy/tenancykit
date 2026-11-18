package dev.ngb.platform.event;

import static org.assertj.core.api.Assertions.assertThat;

import dev.ngb.core.event.SuiteEventTypes;
import dev.ngb.core.replica.ReplicaStream;
import dev.ngb.platform.support.RepositoryPaths;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * The event types Platform produces are exactly the schemas under {@code contracts/events/platform}: the one list
 * of constants, the schema files, the broker exports and the replica's streams are one set.
 */
class EventTypeCatalogTest {

    private static final String SUFFIX = ".schema.json";

    @Test
    void theSharedEventTypesAreTheSchemaFiles() throws IOException {
        assertThat(SuiteEventTypes.platform()).as("SuiteEventTypes and contracts/events/platform").containsExactlyInAnyOrderElementsOf(schemaTypes());
    }

    @Test
    void everyReplicaStreamCarriesATypeTheSuitePublishes() {
        assertThat(ReplicaStream.eventTypes()).allMatch(SuiteEventTypes.platform()::contains);
    }

    private static Set<String> schemaTypes() throws IOException {
        Path directory = RepositoryPaths.repositoryRoot().resolve("contracts/events/platform");
        assertThat(directory).as("contracts/events/platform is missing").isDirectory();
        try (Stream<Path> files = Files.list(directory)) {
            Set<String> types = new TreeSet<>();
            files.map(file -> file.getFileName().toString())
                    .filter(name -> name.endsWith(SUFFIX))
                    .forEach(name -> types.add(name.substring(0, name.length() - SUFFIX.length())));
            return types;
        }
    }
}
