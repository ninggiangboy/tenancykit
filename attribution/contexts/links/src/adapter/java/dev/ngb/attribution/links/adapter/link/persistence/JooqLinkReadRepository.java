package dev.ngb.attribution.links.adapter.link.persistence;

import static dev.ngb.attribution_jooq.Tables.LINKS;
import static dev.ngb.attribution_jooq.Tables.LINK_DESTINATIONS;
import static org.jooq.impl.DSL.noCondition;

import dev.ngb.attribution.links.application.link.query.LinkListing;
import dev.ngb.attribution.links.application.link.query.LinkReadRepository;
import dev.ngb.attribution.links.application.link.query.LinkView;
import dev.ngb.attribution_jooq.tables.records.LinkDestinationsRecord;
import dev.ngb.attribution_jooq.tables.records.LinksRecord;
import dev.ngb.core.query.ListQuery;
import dev.ngb.core.query.Page;
import dev.ngb.core.spring.query.JooqLists;
import dev.ngb.core.spring.query.ListBinding;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jooq.DSLContext;
import tools.jackson.databind.json.JsonMapper;

/**
 * The link listing and detail. A link and its destinations are read together, so the view never shows a link and half
 * its destinations.
 *
 * <p>The listing compiles the caller's {@link ListQuery} against the columns {@link LinkListing} whitelists. A page's
 * destinations are then read in one query keyed by the page's link ids rather than one query per link, so the response
 * costs two statements whatever its page size.
 */
public class JooqLinkReadRepository implements LinkReadRepository {

    /** The link fields a caller may filter and sort on, bound to their columns. */
    private static final ListBinding BINDING = ListBinding.of(LinkListing.SCHEMA)
            .column("id", LINKS.ID)
            .column("campaign_id", LINKS.CAMPAIGN_ID)
            .column("slug", LINKS.SLUG)
            .column("kind", LINKS.KIND)
            .column("external_ref", LINKS.EXTERNAL_REF)
            .column("status", LINKS.STATUS)
            .column("expires_at", LINKS.EXPIRES_AT)
            .column("max_clicks", LINKS.MAX_CLICKS)
            .column("created_at", LINKS.CREATED_AT)
            .column("updated_at", LINKS.UPDATED_AT)
            .build();

    private final DSLContext dsl;
    private final JsonMapper json;

    public JooqLinkReadRepository(DSLContext dsl, JsonMapper json) {
        this.dsl = dsl;
        this.json = json;
    }

    /** @return the binding of the link listing, for the test that checks every sortable field has an index */
    public static ListBinding listBinding() {
        return BINDING;
    }

    @Override
    public Page<LinkView> list(ListQuery query) {
        return JooqLists.fetchPage(dsl, LINKS, noCondition(), BINDING, query, this::toViews);
    }

    @Override
    public Optional<LinkView> find(UUID linkId) {
        return dsl.selectFrom(LINKS)
                .where(LINKS.ID.eq(linkId))
                .fetchOptional(row -> toView(row, destinations(List.of(linkId)).getOrDefault(linkId, List.of())));
    }

    private List<LinkView> toViews(List<LinksRecord> rows) {
        Map<UUID, List<LinkView.DestinationView>> byLink =
                destinations(rows.stream().map(LinksRecord::getId).toList());
        return rows.stream()
                .map(row -> toView(row, byLink.getOrDefault(row.getId(), List.of())))
                .toList();
    }

    /** @return every destination of {@code linkIds}, grouped by link, in one query */
    private Map<UUID, List<LinkView.DestinationView>> destinations(List<UUID> linkIds) {
        if (linkIds.isEmpty()) {
            return Map.of();
        }
        return dsl.selectFrom(LINK_DESTINATIONS)
                .where(LINK_DESTINATIONS.LINK_ID.in(linkIds))
                .orderBy(LINK_DESTINATIONS.POSITION.asc())
                .fetchGroups(LINK_DESTINATIONS.LINK_ID, this::toDestination);
    }

    private LinkView.DestinationView toDestination(LinkDestinationsRecord row) {
        return new LinkView.DestinationView(
                row.getId(),
                row.getUrl(),
                row.getPosition(),
                row.getWeight(),
                row.getIsFallback(),
                matchDevices(row.getMatch()),
                matchCountries(row.getMatch()));
    }

    private LinkView toView(LinksRecord row, List<LinkView.DestinationView> destinations) {
        return new LinkView(
                row.getId(),
                row.getCampaignId(),
                row.getSlug(),
                row.getKind(),
                row.getExternalRef(),
                row.getStatus(),
                null,
                row.getExpiresAt(),
                row.getMaxClicks(),
                row.getPasswordHash() != null,
                readUtm(row.getUtm()),
                destinations,
                row.getVersion(),
                row.getCreatedAt(),
                row.getUpdatedAt());
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
