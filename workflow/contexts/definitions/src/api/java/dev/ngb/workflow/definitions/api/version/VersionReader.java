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
}
