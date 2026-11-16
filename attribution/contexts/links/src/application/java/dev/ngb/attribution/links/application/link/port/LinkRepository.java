package dev.ngb.attribution.links.application.link.port;

import dev.ngb.attribution.links.domain.link.Link;
import java.util.Optional;
import java.util.UUID;

/** The write side of links, including the atomic click counter the redirect spends. */
public interface LinkRepository {

    void insert(Link link);

    Optional<Link> findById(UUID linkId);

    /** The link a public slug names, within the current tenant, for the redirect's cache to load. */
    Optional<Link> findBySlug(String slug);

    boolean update(Link link, long expectedVersion);

    /** Whether the tenant already uses a slug, so a generated one is retried instead of failing the insert. */
    boolean slugTaken(String slug);

    /**
     * Spends one click against a link's cap in one statement.
     *
     * @return true when a click was counted, false when the link has already reached {@code cap}
     */
    boolean incrementIfBelow(UUID linkId, long cap);

    long clicksCounted(UUID linkId);
}
