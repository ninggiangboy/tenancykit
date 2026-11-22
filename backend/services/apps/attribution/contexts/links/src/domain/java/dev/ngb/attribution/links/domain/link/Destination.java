package dev.ngb.attribution.links.domain.link;

import dev.ngb.core.error.BadRequestException;
import java.util.Objects;
import java.util.UUID;

/**
 * One destination of a link: where a visitor goes, how often it is chosen among the survivors of the match, and the
 * rule that decides whether it is a survivor at all.
 *
 * @param position the order the caller gave the destination, kept so a report and an edit are stable
 * @param weight a positive integer; the weighted choice among survivors uses it
 * @param fallback whether this is the one destination a link falls back to when no rule passes
 */
public record Destination(UUID id, DestinationUrl url, int position, int weight, DeviceMatch match, boolean fallback) {

    public static final int MAX_WEIGHT = 1000;

    public Destination {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(url, "url");
        Objects.requireNonNull(match, "match");
        if (position < 0) {
            throw new BadRequestException("A destination's position is not negative.");
        }
        if (weight < 1 || weight > MAX_WEIGHT) {
            throw new BadRequestException("A destination's weight is between 1 and " + MAX_WEIGHT + ".");
        }
    }
}
