package dev.ngb.mailroom.content.application.template.port;

import dev.ngb.mailroom.content.domain.template.Template;
import dev.ngb.mailroom.content.domain.template.TemplateRevision;
import dev.ngb.mailroom.shared.TemplateKey;
import java.util.Optional;
import java.util.UUID;

/** The write side of a tenant's templates and their immutable revisions. */
public interface TemplateRepository {

    /** Inserts the template pointer and its first revision in the caller's transaction. */
    void insert(Template template, TemplateRevision firstRevision);

    /**
     * Appends a revision and moves the pointer, guarded by the version the caller read.
     *
     * @return false when another writer moved the pointer first
     */
    boolean addRevision(Template template, TemplateRevision revision, long expectedVersion);

    /** Deletes the pointer and leaves the revisions, so a pinned campaign still renders. */
    boolean delete(UUID templateId, long expectedVersion);

    Optional<Template> findById(UUID templateId);

    Optional<Template> findByKey(TemplateKey key);

    Optional<TemplateRevision> findRevision(UUID templateId, int revision);
}
