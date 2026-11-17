package dev.ngb.attribution.links.api.link;

import java.util.Optional;
import java.util.UUID;

/**
 * The one link fact insight needs for its statistics endpoint: the link's status and click cap.
 *
 * <p>The numbers come from the rollups insight owns; the link row belongs to links. This api is the seam, so insight
 * never reads the link table and links never reaches into a rollup.
 */
public interface LinkMetadataReader {

    /**
     * One link's metadata.
     *
     * @return the metadata, or empty when the link does not exist in this tenant
     */
    Optional<LinkMetadata> link(UUID linkId);

    /** One link's metadata. */
    record LinkMetadata(UUID linkId, UUID campaignId, String status, Long maxClicks) {}
}
