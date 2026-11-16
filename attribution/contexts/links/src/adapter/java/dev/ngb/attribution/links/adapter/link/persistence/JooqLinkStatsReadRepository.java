package dev.ngb.attribution.links.adapter.link.persistence;

import static dev.ngb.attribution_jooq.Tables.LINK_COUNTERS;
import static dev.ngb.attribution_jooq.Tables.LINKS;

import dev.ngb.attribution.links.application.link.query.LinkStatsReadRepository;
import dev.ngb.attribution.links.application.link.query.LinkStatsView;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jooq.DSLContext;

/**
 * A link's click totals. Until the click ledger exists the total is the counter a capped link keeps and the daily series
 * is empty; the thirty-day breakdown arrives with the store that can answer it.
 */
public class JooqLinkStatsReadRepository implements LinkStatsReadRepository {

    private final DSLContext dsl;

    public JooqLinkStatsReadRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    @Override
    public Optional<LinkStatsView> stats(UUID linkId) {
        var link = dsl.select(LINKS.STATUS, LINKS.MAX_CLICKS)
                .from(LINKS)
                .where(LINKS.ID.eq(linkId))
                .fetchOne();
        if (link == null) {
            return Optional.empty();
        }
        Long counted = dsl.select(LINK_COUNTERS.CLICKS_COUNTED)
                .from(LINK_COUNTERS)
                .where(LINK_COUNTERS.LINK_ID.eq(linkId))
                .fetchOne(LINK_COUNTERS.CLICKS_COUNTED);
        return Optional.of(new LinkStatsView(
                counted == null ? 0 : counted,
                link.value2(),
                link.value1(),
                List.of()));
    }
}
