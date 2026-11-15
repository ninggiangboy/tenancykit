package dev.ngb.mailroom.audience.adapter.segment.persistence;

import static dev.ngb.mailroom_jooq.Tables.SEGMENTS;

import dev.ngb.mailroom.audience.application.segment.query.SegmentReadRepository;
import dev.ngb.mailroom.audience.application.segment.query.SegmentView;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/** The segment listing. */
public class JooqSegmentReadRepository implements SegmentReadRepository {

    private static final TypeReference<Map<String, Object>> FILTER = new TypeReference<>() {};

    private final DSLContext dsl;
    private final JsonMapper json;

    public JooqSegmentReadRepository(DSLContext dsl, JsonMapper json) {
        this.dsl = dsl;
        this.json = json;
    }

    @Override
    public List<SegmentView> list(UUID afterId, int limit) {
        Condition where = afterId == null ? DSL.noCondition() : SEGMENTS.ID.lt(afterId);
        return dsl.select(
                        SEGMENTS.ID, SEGMENTS.NAME, SEGMENTS.FILTER, SEGMENTS.VERSION,
                        SEGMENTS.CREATED_AT, SEGMENTS.UPDATED_AT)
                .from(SEGMENTS)
                .where(where)
                .orderBy(SEGMENTS.ID.desc())
                .limit(limit)
                .fetch(row -> new SegmentView(
                        row.value1(),
                        row.value2(),
                        json.readValue(row.value3().data(), FILTER),
                        row.value4(),
                        row.value5(),
                        row.value6()));
    }

    @Override
    public Optional<SegmentView> find(UUID segmentId) {
        return dsl.select(
                        SEGMENTS.ID, SEGMENTS.NAME, SEGMENTS.FILTER, SEGMENTS.VERSION,
                        SEGMENTS.CREATED_AT, SEGMENTS.UPDATED_AT)
                .from(SEGMENTS)
                .where(SEGMENTS.ID.eq(segmentId))
                .fetchOptional(row -> new SegmentView(
                        row.value1(),
                        row.value2(),
                        json.readValue(row.value3().data(), FILTER),
                        row.value4(),
                        row.value5(),
                        row.value6()));
    }
}
