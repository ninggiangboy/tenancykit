package dev.ngb.attribution.links.adapter.link.persistence;

import static dev.ngb.attribution_jooq.Tables.LINK_COUNTERS;
import static dev.ngb.attribution_jooq.Tables.LINK_DESTINATIONS;
import static dev.ngb.attribution_jooq.Tables.LINKS;

import dev.ngb.attribution.links.application.link.port.LinkRepository;
import dev.ngb.attribution.links.domain.link.Destination;
import dev.ngb.attribution.links.domain.link.DestinationUrl;
import dev.ngb.attribution.links.domain.link.DeviceMatch;
import dev.ngb.attribution.links.domain.link.Link;
import dev.ngb.attribution.links.domain.link.LinkKind;
import dev.ngb.attribution.links.domain.link.LinkStatus;
import dev.ngb.attribution.links.domain.link.Slug;
import dev.ngb.core.id.TenantId;
import dev.ngb.core.visitor.DeviceClass;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.JSONB;
import org.jooq.impl.DSL;
import tools.jackson.databind.json.JsonMapper;

/**
 * The link store. A link and its destinations are written together and a link is read back with its destinations, so a
 * redirect never sees a link whose destinations are half written. The click counter is its own row because a capped
 * link updates it on every click and must not touch the link's version.
 */
public class JooqLinkRepository implements LinkRepository {

    private final DSLContext dsl;
    private final JsonMapper json;

    public JooqLinkRepository(DSLContext dsl, JsonMapper json) {
        this.dsl = dsl;
        this.json = json;
    }

    @Override
    public void insert(Link link) {
        dsl.insertInto(LINKS)
                .set(LINKS.ID, link.id())
                .set(LINKS.TENANT_ID, link.tenantId().value())
                .set(LINKS.CAMPAIGN_ID, link.campaignId())
                .set(LINKS.SLUG, link.slug().value())
                .set(LINKS.KIND, link.kind().name())
                .set(LINKS.EXTERNAL_REF, link.externalRef())
                .set(LINKS.STATUS, link.status().name())
                .set(LINKS.EXPIRES_AT, link.expiresAt())
                .set(LINKS.MAX_CLICKS, link.maxClicks())
                .set(LINKS.PASSWORD_HASH, link.passwordHash())
                .set(LINKS.UTM, JSONB.valueOf(write(link.utm())))
                .set(LINKS.VERSION, link.version())
                .set(LINKS.CREATED_AT, link.createdAt())
                .set(LINKS.UPDATED_AT, link.updatedAt())
                .execute();
        insertDestinations(link);
        if (link.maxClicks() != null) {
            dsl.insertInto(LINK_COUNTERS)
                    .set(LINK_COUNTERS.TENANT_ID, link.tenantId().value())
                    .set(LINK_COUNTERS.LINK_ID, link.id())
                    .set(LINK_COUNTERS.CLICKS_COUNTED, 0L)
                    .execute();
        }
    }

    @Override
    public Optional<Link> findById(UUID linkId) {
        return dsl.selectFrom(LINKS).where(LINKS.ID.eq(linkId)).fetchOptional(this::toLink);
    }

    @Override
    public boolean update(Link link, long expectedVersion) {
        boolean updated = dsl.update(LINKS)
                        .set(LINKS.CAMPAIGN_ID, link.campaignId())
                        .set(LINKS.EXTERNAL_REF, link.externalRef())
                        .set(LINKS.STATUS, link.status().name())
                        .set(LINKS.EXPIRES_AT, link.expiresAt())
                        .set(LINKS.MAX_CLICKS, link.maxClicks())
                        .set(LINKS.PASSWORD_HASH, link.passwordHash())
                        .set(LINKS.UTM, JSONB.valueOf(write(link.utm())))
                        .set(LINKS.VERSION, link.version())
                        .set(LINKS.UPDATED_AT, link.updatedAt())
                        .where(LINKS.ID.eq(link.id()))
                        .and(LINKS.VERSION.eq(expectedVersion))
                        .execute()
                == 1;
        if (!updated) {
            return false;
        }
        dsl.deleteFrom(LINK_DESTINATIONS).where(LINK_DESTINATIONS.LINK_ID.eq(link.id())).execute();
        insertDestinations(link);
        ensureCounter(link);
        return true;
    }

    @Override
    public boolean slugTaken(String slug) {
        return dsl.fetchExists(DSL.selectOne().from(LINKS).where(LINKS.SLUG.eq(slug)));
    }

    @Override
    public boolean incrementIfBelow(UUID linkId, long cap) {
        return dsl.update(LINK_COUNTERS)
                        .set(LINK_COUNTERS.CLICKS_COUNTED, LINK_COUNTERS.CLICKS_COUNTED.plus(1))
                        .where(LINK_COUNTERS.LINK_ID.eq(linkId))
                        .and(LINK_COUNTERS.CLICKS_COUNTED.lt(cap))
                        .execute()
                == 1;
    }

    @Override
    public long clicksCounted(UUID linkId) {
        Long counted = dsl.select(LINK_COUNTERS.CLICKS_COUNTED)
                .from(LINK_COUNTERS)
                .where(LINK_COUNTERS.LINK_ID.eq(linkId))
                .fetchOne(LINK_COUNTERS.CLICKS_COUNTED);
        return counted == null ? 0 : counted;
    }

    /** Destinations indexed by position, so one read gives the fallback and the survivors together. */
    List<Destination> loadDestinations(UUID linkId) {
        return dsl.selectFrom(LINK_DESTINATIONS)
                .where(LINK_DESTINATIONS.LINK_ID.eq(linkId))
                .orderBy(LINK_DESTINATIONS.POSITION.asc())
                .fetch(row -> new Destination(
                        row.getId(),
                        new DestinationUrl(row.getUrl()),
                        row.getPosition(),
                        row.getWeight(),
                        readMatch(row.getMatch()),
                        row.getIsFallback()));
    }

    private void insertDestinations(Link link) {
        for (Destination destination : link.destinations()) {
            dsl.insertInto(LINK_DESTINATIONS)
                    .set(LINK_DESTINATIONS.ID, destination.id())
                    .set(LINK_DESTINATIONS.TENANT_ID, link.tenantId().value())
                    .set(LINK_DESTINATIONS.LINK_ID, link.id())
                    .set(LINK_DESTINATIONS.POSITION, destination.position())
                    .set(LINK_DESTINATIONS.URL, destination.url().value())
                    .set(LINK_DESTINATIONS.WEIGHT, destination.weight())
                    .set(LINK_DESTINATIONS.MATCH, JSONB.valueOf(writeMatch(destination.match())))
                    .set(LINK_DESTINATIONS.IS_FALLBACK, destination.fallback())
                    .execute();
        }
    }

    private void ensureCounter(Link link) {
        if (link.maxClicks() == null) {
            dsl.deleteFrom(LINK_COUNTERS).where(LINK_COUNTERS.LINK_ID.eq(link.id())).execute();
            return;
        }
        dsl.insertInto(LINK_COUNTERS)
                .set(LINK_COUNTERS.TENANT_ID, link.tenantId().value())
                .set(LINK_COUNTERS.LINK_ID, link.id())
                .set(LINK_COUNTERS.CLICKS_COUNTED, 0L)
                .onConflict(LINK_COUNTERS.TENANT_ID, LINK_COUNTERS.LINK_ID)
                .doNothing()
                .execute();
    }

    private Link toLink(dev.ngb.attribution_jooq.tables.records.LinksRecord row) {
        return new Link(
                row.getId(),
                new TenantId(row.getTenantId()),
                row.getCampaignId(),
                Slug.of(row.getSlug()),
                LinkKind.valueOf(row.getKind()),
                row.getExternalRef(),
                LinkStatus.valueOf(row.getStatus()),
                row.getExpiresAt(),
                row.getMaxClicks(),
                row.getPasswordHash(),
                readUtm(row.getUtm()),
                loadDestinations(row.getId()),
                row.getVersion(),
                row.getCreatedAt(),
                row.getUpdatedAt());
    }

    private Map<String, String> readUtm(JSONB value) {
        if (value == null) {
            return Map.of();
        }
        return json.readValue(value.data(), new tools.jackson.core.type.TypeReference<Map<String, String>>() {});
    }

    private DeviceMatch readMatch(JSONB value) {
        if (value == null) {
            return DeviceMatch.any();
        }
        MatchJson match = json.readValue(value.data(), MatchJson.class);
        List<DeviceClass> devices = match.device() == null
                ? List.<DeviceClass>of()
                : match.device().stream().map(DeviceClass::valueOf).toList();
        return new DeviceMatch(devices, match.country());
    }

    private String write(Map<String, String> utm) {
        return json.writeValueAsString(utm);
    }

    private String writeMatch(DeviceMatch match) {
        return json.writeValueAsString(new MatchJson(
                match.devices().stream().map(DeviceClass::name).toList(), match.countries()));
    }

    /** The stored shape of a destination's rule: two lists, either of which may be empty. */
    record MatchJson(List<String> device, List<String> country) {}
}
