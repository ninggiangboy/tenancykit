package dev.ngb.attribution.links.adapter.link.persistence;

import static dev.ngb.attribution_jooq.Tables.LINKS;
import static dev.ngb.attribution_jooq.Tables.LINK_DESTINATIONS;

import dev.ngb.attribution.links.application.link.query.LinkReadRepository;
import dev.ngb.attribution.links.application.link.query.LinkView;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.impl.DSL;
import tools.jackson.databind.json.JsonMapper;

/**
 * The link listing and detail. A link and its destinations are read in one query so the view never shows a link and half
 * its destinations.
 */
public class JooqLinkReadRepository implements LinkReadRepository {

    private final DSLContext dsl;
    private final JsonMapper json;

    public JooqLinkReadRepository(DSLContext dsl, JsonMapper json) {
        this.dsl = dsl;
        this.json = json;
    }

    @Override
    public List<LinkView> list(UUID campaignId, String externalRef, String status, UUID afterId, int limit) {
        Condition where = DSL.noCondition();
        if (campaignId != null) {
            where = where.and(LINKS.CAMPAIGN_ID.eq(campaignId));
        }
        if (externalRef != null && !externalRef.isBlank()) {
            where = where.and(LINKS.EXTERNAL_REF.eq(externalRef.strip()));
        }
        if (status != null && !status.isBlank()) {
            where = where.and(LINKS.STATUS.eq(status.strip().toUpperCase(java.util.Locale.ROOT)));
        }
        if (afterId != null) {
            where = where.and(LINKS.ID.lt(afterId));
        }
        return dsl.select(
                        LINKS.ID, LINKS.CAMPAIGN_ID, LINKS.SLUG, LINKS.KIND, LINKS.EXTERNAL_REF, LINKS.STATUS,
                        LINKS.EXPIRES_AT, LINKS.MAX_CLICKS, LINKS.PASSWORD_HASH, LINKS.UTM, LINKS.VERSION,
                        LINKS.CREATED_AT, LINKS.UPDATED_AT)
                .from(LINKS)
                .where(where)
                .orderBy(LINKS.ID.desc())
                .limit(limit)
                .fetch(this::toView);
    }

    @Override
    public Optional<LinkView> find(UUID linkId) {
        return dsl.select(
                        LINKS.ID, LINKS.CAMPAIGN_ID, LINKS.SLUG, LINKS.KIND, LINKS.EXTERNAL_REF, LINKS.STATUS,
                        LINKS.EXPIRES_AT, LINKS.MAX_CLICKS, LINKS.PASSWORD_HASH, LINKS.UTM, LINKS.VERSION,
                        LINKS.CREATED_AT, LINKS.UPDATED_AT)
                .from(LINKS)
                .where(LINKS.ID.eq(linkId))
                .fetchOptional(this::toView);
    }

    private LinkView toView(Record row) {
        UUID linkId = row.get(LINKS.ID);
        List<LinkView.DestinationView> destinations = dsl.selectFrom(LINK_DESTINATIONS)
                .where(LINK_DESTINATIONS.LINK_ID.eq(linkId))
                .orderBy(LINK_DESTINATIONS.POSITION.asc())
                .fetch(destination -> new LinkView.DestinationView(
                        destination.getId(),
                        destination.getUrl(),
                        destination.getPosition(),
                        destination.getWeight(),
                        destination.getIsFallback(),
                        matchDevices(destination.getMatch()),
                        matchCountries(destination.getMatch())));
        return new LinkView(
                linkId,
                row.get(LINKS.CAMPAIGN_ID),
                row.get(LINKS.SLUG),
                row.get(LINKS.KIND),
                row.get(LINKS.EXTERNAL_REF),
                row.get(LINKS.STATUS),
                null,
                row.get(LINKS.EXPIRES_AT),
                row.get(LINKS.MAX_CLICKS),
                row.get(LINKS.PASSWORD_HASH) != null,
                readUtm(row.get(LINKS.UTM)),
                destinations,
                row.get(LINKS.VERSION),
                row.get(LINKS.CREATED_AT),
                row.get(LINKS.UPDATED_AT));
    }

    private List<String> matchDevices(org.jooq.JSONB value) {
        if (value == null) {
            return List.of();
        }
        MatchJson match = json.readValue(value.data(), MatchJson.class);
        return match.device() == null ? List.of() : match.device();
    }

    private List<String> matchCountries(org.jooq.JSONB value) {
        if (value == null) {
            return List.of();
        }
        MatchJson match = json.readValue(value.data(), MatchJson.class);
        return match.country() == null ? List.of() : match.country();
    }

    private Map<String, String> readUtm(org.jooq.JSONB value) {
        if (value == null) {
            return Map.of();
        }
        return json.readValue(value.data(), new tools.jackson.core.type.TypeReference<Map<String, String>>() {});
    }

    /** The stored shape of a destination's rule. */
    record MatchJson(List<String> device, List<String> country) {}
}
