package dev.ngb.loyalty.program.adapter.promotion.persistence;

import static dev.ngb.loyalty_jooq.Tables.PROMOTIONS;

import dev.ngb.core.id.TenantId;
import dev.ngb.loyalty.program.api.promotion.PromotionStatus;
import dev.ngb.loyalty.program.application.promotion.port.PromotionRepository;
import dev.ngb.loyalty.program.domain.promotion.Promotion;
import dev.ngb.loyalty.program.domain.promotion.PromotionId;
import dev.ngb.loyalty_jooq.tables.records.PromotionsRecord;
import java.util.Optional;
import org.jooq.DSLContext;
import org.jooq.JSONB;

public class JooqPromotionRepository implements PromotionRepository {

    private final DSLContext dsl;
    private final PromotionConditionsJson conditions;

    public JooqPromotionRepository(DSLContext dsl, PromotionConditionsJson conditions) {
        this.dsl = dsl;
        this.conditions = conditions;
    }

    @Override
    public void add(Promotion p) {
        dsl.insertInto(PROMOTIONS)
                .set(PROMOTIONS.TENANT_ID, p.tenantId().value())
                .set(PROMOTIONS.ID, p.id().value())
                .set(PROMOTIONS.NAME, p.name())
                .set(PROMOTIONS.STATUS, p.status().name())
                .set(PROMOTIONS.PRIORITY, p.priority())
                .set(PROMOTIONS.MULTIPLIER, p.multiplier())
                .set(PROMOTIONS.STARTS_AT, p.startsAt())
                .set(PROMOTIONS.ENDS_AT, p.endsAt())
                .set(PROMOTIONS.CONDITIONS, JSONB.valueOf(conditions.write(p.conditions())))
                .set(PROMOTIONS.VERSION, p.version())
                .set(PROMOTIONS.CREATED_AT, p.createdAt())
                .set(PROMOTIONS.UPDATED_AT, p.updatedAt())
                .execute();
    }

    @Override
    public void update(Promotion p) {
        int updated = dsl.update(PROMOTIONS)
                .set(PROMOTIONS.NAME, p.name())
                .set(PROMOTIONS.STATUS, p.status().name())
                .set(PROMOTIONS.PRIORITY, p.priority())
                .set(PROMOTIONS.MULTIPLIER, p.multiplier())
                .set(PROMOTIONS.STARTS_AT, p.startsAt())
                .set(PROMOTIONS.ENDS_AT, p.endsAt())
                .set(PROMOTIONS.CONDITIONS, JSONB.valueOf(conditions.write(p.conditions())))
                .set(PROMOTIONS.UPDATED_AT, p.updatedAt())
                .set(PROMOTIONS.VERSION, p.version())
                .where(PROMOTIONS.ID.eq(p.id().value()))
                .and(PROMOTIONS.TENANT_ID.eq(p.tenantId().value()))
                .and(PROMOTIONS.VERSION.lt(p.version()))
                .execute();
        if (updated != 1) {
            throw new IllegalStateException(
                    "promotion " + p.id() + " was not updated: version " + p.version() + " is not newer");
        }
    }

    @Override
    public Optional<Promotion> lockById(PromotionId id) {
        return dsl.selectFrom(PROMOTIONS)
                .where(PROMOTIONS.ID.eq(id.value()))
                .forNoKeyUpdate()
                .fetchOptional(this::toDomain);
    }

    @Override
    public long countOpen() {
        return dsl.fetchCount(PROMOTIONS, PROMOTIONS.STATUS.ne(PromotionStatus.ARCHIVED.name()));
    }

    private Promotion toDomain(PromotionsRecord row) {
        return Promotion.restore(
                new TenantId(row.getTenantId()),
                new PromotionId(row.getId()),
                row.getName(),
                row.getPriority(),
                PromotionStatus.valueOf(row.getStatus()),
                row.getMultiplier(),
                row.getStartsAt(),
                row.getEndsAt(),
                conditions.read(row.getConditions() == null ? null : row.getConditions().data()),
                row.getCreatedAt(),
                row.getUpdatedAt(),
                row.getVersion());
    }
}
