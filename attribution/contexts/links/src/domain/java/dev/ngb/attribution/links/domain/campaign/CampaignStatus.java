package dev.ngb.attribution.links.domain.campaign;

/** A campaign is either live or archived; an archived campaign accepts no new link. */
public enum CampaignStatus {
    ACTIVE,
    ARCHIVED;

    public boolean archived() {
        return this == ARCHIVED;
    }
}
