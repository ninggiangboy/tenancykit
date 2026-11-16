package dev.ngb.attribution.links.domain.redirect;

import dev.ngb.attribution.links.domain.link.Destination;
import dev.ngb.attribution.links.domain.link.Link;
import dev.ngb.core.visitor.DeviceClass;
import java.util.List;
import java.util.random.RandomGenerator;

/**
 * Picks the destination a visitor is sent to: a weighted draw among the destinations whose rule passes, and the one
 * fallback when none does.
 *
 * <p>The draw is over weights, not positions, so an A/B split is a data change rather than a code change. A link always
 * has exactly one fallback, so the answer is never empty; the selector is pure and takes its randomness, so a test can
 * pin the distribution.
 */
public final class DestinationSelector {

    private DestinationSelector() {}

    /**
     * @param link the link being followed
     * @param device the visitor's classified device
     * @param country the visitor's country, or null/blank when GeoIP had none
     * @param random the source of randomness, injected so a test is deterministic
     */
    public static Destination select(Link link, DeviceClass device, String country, RandomGenerator random) {
        List<Destination> survivors = link.destinations().stream()
                .filter(destination -> destination.match().passes(device, country))
                .toList();
        if (survivors.isEmpty()) {
            return link.fallback();
        }
        long total = survivors.stream().mapToLong(Destination::weight).sum();
        if (total <= 0) {
            return survivors.getFirst();
        }
        long draw = random.nextLong(total);
        long running = 0;
        for (Destination destination : survivors) {
            running += destination.weight();
            if (draw < running) {
                return destination;
            }
        }
        // Only reachable if the weights changed between the sum and the loop, which this pure function cannot do.
        return survivors.getLast();
    }
}
