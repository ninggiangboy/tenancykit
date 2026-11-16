package dev.ngb.loyalty.program.adapter.promotion.persistence;

import static dev.ngb.loyalty_jooq.Tables.PROMOTIONS;
import static org.jooq.impl.DSL.noCondition;

import dev.ngb.core.query.ListQuery;
import dev.ngb.core.query.Page;
import dev.ngb.core.spring.query.JooqLists;
import dev.ngb.core.spring.query.ListBinding;
import dev.ngb.loyalty.program.api.promotion.PromotionStatus;
import dev.ngb.loyalty.program.api.promotion.PromotionView;
import dev.ngb.loyalty.program.application.promotion.query.PromotionListing;
import dev.ngb.loyalty.program.application.promotion.query.PromotionReadRepository;
import dev.ngb.loyalty_jooq.tables.records.PromotionsRecord;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jooq.Condition;
import org.jooq.DSLContext;

/** Selects promotions straight into the view; the aggregate is never built on a read. */
public class JooqPromotionReadRepository implements PromotionReadRepository {

    /** The promotion fields a caller may filter and sort on, bound to their columns. */
    private static final ListBinding BINDING = ListBinding.of(PromotionListing.SCHEMA)
            .column("id", PROMOTIONS.ID)
            .column("name", PROMOTIONS.NAME)
            .column("status", PROMOTIONS.STATUS)
            .column("priority", PROMOTIONS.PRIORITY)
            .column("multiplier", PROMOTIONS.MULTIPLIER)
            .column("starts_at", PROMOTIONS.STARTS_AT)
            .column("ends_at", PROMOTIONS.ENDS_AT)
            .column("created_at", PROMOTIONS.CREATED_AT)
            .column("updated_at", PROMOTIONS.UPDATED_AT)
            .build();

    private final DSLContext dsl;
    private final PromotionConditionsJson conditions;

    public JooqPromotionReadRepository(DSLContext dsl, PromotionConditionsJson conditions) {
        this.dsl = dsl;
        this.conditions = conditions;
    }

    @Override
    public Page<PromotionView> list(ListQuery query, Boolean active, ZoneId zone) {
        Condition scope = active == null
                ? noCondition()
                : active
                        ? PROMOTIONS.STATUS.eq(PromotionStatus.ACTIVE.name())
                        : PROMOTIONS.STATUS.ne(PromotionStatus.ACTIVE.name());
        return JooqLists.fetch(dsl, PROMOTIONS, scope, BINDING, query, row -> toView(row, zone));
    }

    @Override
    public Optional<PromotionView> findById(UUID id, ZoneId zone) {
        return dsl.selectFrom(PROMOTIONS)
                .where(PROMOTIONS.ID.eq(id))
                .fetchOptional(row -> toView(row, zone));
    }

    @Override
    public List<PromotionView> activeAt(Instant instant, ZoneId zone) {
        return dsl.selectFrom(PROMOTIONS)
                .where(PROMOTIONS.STATUS.eq(PromotionStatus.ACTIVE.name()))
                .and(PROMOTIONS.STARTS_AT.isNull().or(PROMOTIONS.STARTS_AT.le(instant)))
                .and(PROMOTIONS.ENDS_AT.isNull().or(PROMOTIONS.ENDS_AT.ge(instant)))
                .orderBy(PROMOTIONS.PRIORITY.desc(), PROMOTIONS.ID)
                .fetch(row -> toView(row, zone));
    }

    private PromotionView toView(PromotionsRecord row, ZoneId zone) {
        var stored = conditions.read(row.getConditions() == null ? null : row.getConditions().data());
        return new PromotionView(
                row.getId(),
                row.getName(),
                row.getPriority(),
                PromotionStatus.valueOf(row.getStatus()),
                row.getMultiplier(),
                row.getStartsAt(),
                row.getEndsAt(),
                stored.categories(),
                stored.skus(),
                stored.storeCodes(),
                stored.daysOfWeek(),
                stored.tierCodes(),
                stored.timeFrom(),
                stored.timeTo(),
                zone,
                row.getVersion());
    }
}
