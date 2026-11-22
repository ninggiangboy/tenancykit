package dev.ngb.workflow.definitions.domain.definition;

/** Where a definition stands: never published, published with at least one version, or retired from new instances. */
public enum DefinitionStatus {
    DRAFT,
    PUBLISHED,
    RETIRED
}
