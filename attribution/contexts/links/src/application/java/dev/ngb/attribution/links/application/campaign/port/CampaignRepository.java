package dev.ngb.attribution.links.application.campaign.port;

import dev.ngb.attribution.links.domain.campaign.Campaign;
import java.util.Optional;
import java.util.UUID;

/** The write side of campaigns, guarded by the version every edit compares against. */
public interface CampaignRepository {

    void insert(Campaign campaign);

    Optional<Campaign> findById(UUID campaignId);

    /** Used to derive a unique slug when the caller did not pick one. */
    Optional<Campaign> findBySlug(String slug);

    boolean update(Campaign campaign, long expectedVersion);

    /** Archives the campaign when its stored version still matches; false means another writer moved it. */
    boolean archive(Campaign campaign);

    /** Whether the tenant already uses a slug, so a collision is refused before the insert. */
    boolean slugTaken(String slug);
}
