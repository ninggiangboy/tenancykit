package dev.ngb.attribution.links.adapter.link.persistence;

import static dev.ngb.attribution_jooq.Tables.LINKS;

import dev.ngb.attribution.links.api.link.LinkMetadataReader;
import java.util.Optional;
import java.util.UUID;
import org.jooq.DSLContext;

/** A link's status and click cap, read through the api insight sees. */
public class JooqLinkMetadataReader implements LinkMetadataReader {

    private final DSLContext dsl;

    public JooqLinkMetadataReader(DSLContext dsl) {
        this.dsl = dsl;
    }

    @Override
    public Optional<LinkMetadata> link(UUID linkId) {
        return dsl.select(LINKS.ID, LINKS.CAMPAIGN_ID, LINKS.STATUS, LINKS.MAX_CLICKS)
                .from(LINKS)
                .where(LINKS.ID.eq(linkId))
                .fetchOptional(row -> new LinkMetadata(
                        row.value1(), row.value2(), row.value3(), row.value4()));
    }
}
