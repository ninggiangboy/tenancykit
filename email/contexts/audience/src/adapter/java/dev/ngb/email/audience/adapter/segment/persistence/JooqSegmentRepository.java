package dev.ngb.email.audience.adapter.segment.persistence;

import static dev.ngb.email_jooq.Tables.SEGMENTS;

import dev.ngb.core.id.TenantId;
import dev.ngb.email.audience.application.segment.port.SegmentRepository;
import dev.ngb.email.audience.domain.segment.Segment;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.JSONB;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/** The segment store; the filter is stored as JSONB and revalidated by the aggregate before every write. */
public class JooqSegmentRepository implements SegmentRepository {

    private static final TypeReference<Map<String, Object>> FILTER = new TypeReference<>() {};

    private final DSLContext dsl;
    private final JsonMapper json;

    public JooqSegmentRepository(DSLContext dsl, JsonMapper json) {
        this.dsl = dsl;
        this.json = json;
    }

    @Override
    public void insert(Segment segment) {
        dsl.insertInto(SEGMENTS)
                .set(SEGMENTS.ID, segment.id())
                .set(SEGMENTS.TENANT_ID, segment.tenantId().value())
                .set(SEGMENTS.NAME, segment.name())
                .set(SEGMENTS.FILTER, JSONB.valueOf(json.writeValueAsString(segment.filter())))
                .set(SEGMENTS.VERSION, segment.version())
                .set(SEGMENTS.CREATED_AT, segment.createdAt())
                .set(SEGMENTS.UPDATED_AT, segment.updatedAt())
                .execute();
    }

    @Override
    public boolean update(Segment segment, long expectedVersion) {
        return dsl.update(SEGMENTS)
                        .set(SEGMENTS.NAME, segment.name())
                        .set(SEGMENTS.FILTER, JSONB.valueOf(json.writeValueAsString(segment.filter())))
                        .set(SEGMENTS.VERSION, segment.version())
                        .set(SEGMENTS.UPDATED_AT, segment.updatedAt())
                        .where(SEGMENTS.ID.eq(segment.id()))
                        .and(SEGMENTS.VERSION.eq(expectedVersion))
                        .execute()
                == 1;
    }

    @Override
    public boolean delete(UUID segmentId) {
        return dsl.deleteFrom(SEGMENTS).where(SEGMENTS.ID.eq(segmentId)).execute() == 1;
    }

    @Override
    public Optional<Segment> findById(UUID segmentId) {
        return dsl.selectFrom(SEGMENTS)
                .where(SEGMENTS.ID.eq(segmentId))
                .fetchOptional(row -> new Segment(
                        row.getId(),
                        new TenantId(row.getTenantId()),
                        row.getName(),
                        json.readValue(row.getFilter().data(), FILTER),
                        row.getVersion(),
                        row.getCreatedAt(),
                        row.getUpdatedAt()));
    }
}
