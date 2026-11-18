package dev.ngb.attribution.tracking.adapter.collect.persistence;

import static dev.ngb.attribution_jooq.Tables.CLICK_EVENTS;
import static dev.ngb.attribution_jooq.Tables.VISITOR_CLICKS;

import dev.ngb.attribution.tracking.api.chain.TouchpointReader;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jooq.DSLContext;

/**
 * The touchpoints that led to a conversion, from the stored clicks and the visitor's bindings.
 *
 * <p>The set is the conversion's own click plus every click bound to the visitor inside the window, deduplicated so a
 * click met twice is one touchpoint. Only a human click is a candidate: a bot or suspect click is filtered out here, so
 * the chain the engine sees never has one to mistake for a person. A click after the conversion is excluded, which keeps
 * a late click from stealing an earlier conversion's credit.
 */
public class JooqTouchpointReader implements TouchpointReader {

    private final DSLContext dsl;

    public JooqTouchpointReader(DSLContext dsl) {
        this.dsl = dsl;
    }

    @Override
    public List<Touchpoint> chain(UUID clickId, String visitorId, Instant since, Instant until) {
        // The conversion's own click, fetched directly: it is part of the chain even when the visitor is unknown.
        Map<UUID, Touchpoint> byClick = new LinkedHashMap<>();
        if (clickId != null) {
            addIfInside(byClick, clickId, since, until);
        }
        if (visitorId != null && !visitorId.isBlank()) {
            List<UUID> bound = dsl.select(VISITOR_CLICKS.CLICK_ID)
                    .from(VISITOR_CLICKS)
                    .where(VISITOR_CLICKS.VISITOR_ID.eq(visitorId))
                    .fetch(VISITOR_CLICKS.CLICK_ID);
            for (UUID boundClick : bound) {
                addIfInside(byClick, boundClick, since, until);
            }
        }
        List<Touchpoint> chain = new ArrayList<>(byClick.values());
        chain.sort(java.util.Comparator.comparing(Touchpoint::at));
        return chain;
    }

    private void addIfInside(Map<UUID, Touchpoint> byClick, UUID clickId, Instant since, Instant until) {
        dsl.select(
                        CLICK_EVENTS.CLICK_ID, CLICK_EVENTS.LINK_ID, CLICK_EVENTS.CAMPAIGN_ID, CLICK_EVENTS.OCCURRED_AT,
                        CLICK_EVENTS.TRAFFIC_CLASS)
                .from(CLICK_EVENTS)
                .where(CLICK_EVENTS.CLICK_ID.eq(clickId))
                .and(CLICK_EVENTS.OCCURRED_AT.ge(since))
                .and(CLICK_EVENTS.OCCURRED_AT.le(until))
                .orderBy(CLICK_EVENTS.OCCURRED_AT.desc())
                .limit(1)
                .fetchOptional()
                .ifPresent(row -> {
                    String traffic = row.value5();
                    byClick.put(clickId, new Touchpoint(
                            row.value1(),
                            row.value2(),
                            row.value3(),
                            row.value4(),
                            "HUMAN".equals(traffic)));
                });
    }
}
