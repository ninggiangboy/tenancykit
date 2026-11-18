package dev.ngb.mailroom.audience.adapter.segment.persistence;

import static dev.ngb.mailroom_jooq.Tables.SEGMENTS;
import static org.jooq.impl.DSL.noCondition;

import dev.ngb.core.query.ListQuery;
import dev.ngb.core.query.Page;
import dev.ngb.core.spring.query.JooqLists;
import dev.ngb.core.spring.query.ListBinding;
import dev.ngb.mailroom.audience.application.segment.query.SegmentListing;
import dev.ngb.mailroom.audience.application.segment.query.SegmentReadRepository;
import dev.ngb.mailroom.audience.application.segment.query.SegmentView;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jooq.DSLContext;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/** The segment listing. */
public class JooqSegmentReadRepository implements SegmentReadRepository {

    private static final TypeReference<Map<String, Object>> FILTER = new TypeReference<>() {};

    /** The segment fields a caller may filter and sort on, bound to their columns. */
    private static final ListBinding BINDING = ListBinding.of(SegmentListing.SCHEMA)
            .column("id", SEGMENTS.ID)
            .column("name", SEGMENTS.NAME)
            .column("version", SEGMENTS.VERSION)
            .column("created_at", SEGMENTS.CREATED_AT)
            .column("updated_at", SEGMENTS.UPDATED_AT)
            .build();

    private final DSLContext dsl;
    private final JsonMapper json;

    /** @return the binding of the segment listing, for the test that checks every sortable field has an index */
    public static ListBinding listBinding() {
        return BINDING;
    }

    public JooqSegmentReadRepository(DSLContext dsl, JsonMapper json) {
        this.dsl = dsl;
        this.json = json;
    }

    @Override
    public Page<SegmentView> list(ListQuery query) {
        return JooqLists.fetch(dsl, SEGMENTS, noCondition(), BINDING, query, this::toView);
    }

    @Override
    public Optional<SegmentView> find(UUID segmentId) {
        return dsl.selectFrom(SEGMENTS)
                .where(SEGMENTS.ID.eq(segmentId))
                .fetchOptional(this::toView);
    }

    private SegmentView toView(dev.ngb.mailroom_jooq.tables.records.SegmentsRecord row) {
        return new SegmentView(
                row.getId(),
                row.getName(),
                json.readValue(row.getFilter().data(), FILTER),
                row.getVersion(),
                row.getCreatedAt(),
                row.getUpdatedAt());
    }
}
