package dev.ngb.workflow.definitions.api.version;

import java.util.Optional;
import java.util.UUID;

/**
 * The published version a runtime reads before it starts or resumes an instance.
 *
 * <p>An instance pins a version id, so the graph it walks is the graph that was published, never a later draft. The
 * read is scoped by row-level security like every other read of this context.
 */
public interface VersionReader {

    Optional<PublishedGraph> graph(UUID versionId);

    /** The newest published version of one definition, which is what a trigger starts from. */
    Optional<PublishedGraph> latest(UUID definitionId);

    /**
     * A published version named by definition code, which is what a sub-workflow reaches for.
     *
     * @param version the exact version number, or null for the newest published one
     */
    Optional<PublishedGraph> graphOf(String definitionCode, Integer version);

    /** The code of a definition by id, which an event payload names instead of an internal id. */
    Optional<String> definitionCode(UUID definitionId);
}
