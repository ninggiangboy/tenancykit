package dev.ngb.workflow.definitions.application.definition.port;

import dev.ngb.workflow.definitions.domain.definition.PublishedVersion;

/** The append-only version store. Publishing is the only write; a version is never edited. */
public interface VersionRepository {

    void add(PublishedVersion version);
}
