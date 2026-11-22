package dev.ngb.attribution.insight.adapter.report.web;

import dev.ngb.attribution.insight.application.report.query.LinkStatsQueries;
import dev.ngb.attribution.insight.application.report.query.LinkStatsView;
import dev.ngb.attribution.shared.AttributionPermissions;
import dev.ngb.core.runtime.Clock;
import dev.ngb.core.security.RequiresPermission;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * A link's statistics: its click total, the last thirty days and the bot clicks the default left out.
 *
 * <p>The numbers come from the rollups insight owns; the link's status and cap come from links through its api, so this
 * endpoint lives here rather than in links. It is the same path a caller already knows.
 */
@Tag(
        name = "Link statistics",
        description =
                "A link's click totals and last thirty days.")
@RestController
@RequestMapping("/api/v1/links/{id}/stats")
class LinkStatsController {

    private final LinkStatsQueries stats;
    private final Clock clock;

    LinkStatsController(LinkStatsQueries stats, Clock clock) {
        this.stats = stats;
        this.clock = clock;
    }

    @Operation(
            summary = "Get a link's statistics",
            description =
                    "Returns the link's click total, its clicks per day for the last thirty days, the bot clicks the "
                    + "default leaves out, and its status and click cap. The numbers come from rollups, so `dataThrough` "
                    + "says how fresh they are. Requires `attribution:link:read`.")
    @GetMapping
    @RequiresPermission(AttributionPermissions.LINK_READ)
    LinkStatsView stats(@PathVariable UUID id) {
        return stats.stats(id, clock.now());
    }
}
