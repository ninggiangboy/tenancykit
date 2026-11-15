package dev.ngb.email.audience.adapter.audience.persistence;

import static dev.ngb.email_jooq.Tables.GROUP_MEMBERS;
import static dev.ngb.email_jooq.Tables.RECEIVERS;
import static dev.ngb.email_jooq.Tables.RECEIVER_GROUPS;
import static dev.ngb.email_jooq.Tables.SUPPRESSIONS;

import dev.ngb.email.audience.application.audience.query.AudienceReadRepository;
import dev.ngb.email.audience.application.audience.query.GroupView;
import dev.ngb.email.audience.application.audience.query.ReceiverView;
import dev.ngb.email.audience.application.audience.query.SuppressionView;
import dev.ngb.email.audience.domain.receiver.ReceiverStatus;
import dev.ngb.email.audience.domain.suppression.SuppressionReason;
import dev.ngb.email.audience.domain.suppression.SuppressionScope;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.SortField;
import org.jooq.impl.DSL;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/** The audience read side: receivers, groups, memberships and suppressions, newest first. */
public class JooqAudienceReadRepository implements AudienceReadRepository {

    private static final TypeReference<Map<String, Object>> ATTRIBUTES = new TypeReference<>() {};

    private final DSLContext dsl;
    private final JsonMapper json;

    public JooqAudienceReadRepository(DSLContext dsl, JsonMapper json) {
        this.dsl = dsl;
        this.json = json;
    }

    @Override
    public List<ReceiverView> listReceivers(ReceiverStatus status, String contains, UUID afterId, int limit) {
        Condition where = DSL.noCondition();
        if (status != null) {
            where = where.and(RECEIVERS.STATUS.eq(status.name()));
        }
        if (contains != null && !contains.isBlank()) {
            where = where.and(RECEIVERS.EMAIL.likeIgnoreCase("%" + contains + "%"));
        }
        if (afterId != null) {
            where = where.and(RECEIVERS.ID.lt(afterId));
        }
        return receiverQuery(where, RECEIVERS.ID.desc(), limit);
    }

    @Override
    public Optional<ReceiverView> findReceiver(UUID receiverId) {
        return dsl.select(
                        RECEIVERS.ID, RECEIVERS.EMAIL, RECEIVERS.FIRST_NAME, RECEIVERS.LAST_NAME,
                        RECEIVERS.ATTRIBUTES, RECEIVERS.STATUS, RECEIVERS.VERSION,
                        RECEIVERS.CREATED_AT, RECEIVERS.UPDATED_AT)
                .from(RECEIVERS)
                .where(RECEIVERS.ID.eq(receiverId))
                .fetchOptional(this::toView);
    }

    @Override
    public List<GroupView> listGroups(UUID afterId, int limit) {
        Condition where = afterId == null ? DSL.noCondition() : RECEIVER_GROUPS.ID.lt(afterId);
        return dsl.select(
                        RECEIVER_GROUPS.ID, RECEIVER_GROUPS.NAME, RECEIVER_GROUPS.VERSION,
                        RECEIVER_GROUPS.CREATED_AT, RECEIVER_GROUPS.UPDATED_AT)
                .from(RECEIVER_GROUPS)
                .where(where)
                .orderBy(RECEIVER_GROUPS.ID.desc())
                .limit(limit)
                .fetch(row -> new GroupView(
                        row.value1(), row.value2(), row.value3(), row.value4(), row.value5()));
    }

    @Override
    public Optional<GroupView> findGroup(UUID groupId) {
        return dsl.select(
                        RECEIVER_GROUPS.ID, RECEIVER_GROUPS.NAME, RECEIVER_GROUPS.VERSION,
                        RECEIVER_GROUPS.CREATED_AT, RECEIVER_GROUPS.UPDATED_AT)
                .from(RECEIVER_GROUPS)
                .where(RECEIVER_GROUPS.ID.eq(groupId))
                .fetchOptional(row -> new GroupView(
                        row.value1(), row.value2(), row.value3(), row.value4(), row.value5()));
    }

    @Override
    public List<ReceiverView> pageGroupReceivers(UUID groupId, UUID afterReceiverId, int limit) {
        Condition where = RECEIVERS.ID.in(
                DSL.select(GROUP_MEMBERS.RECEIVER_ID).from(GROUP_MEMBERS).where(GROUP_MEMBERS.GROUP_ID.eq(groupId)));
        if (afterReceiverId != null) {
            where = where.and(RECEIVERS.ID.gt(afterReceiverId));
        }
        return receiverQuery(where, RECEIVERS.ID.asc(), limit);
    }

    @Override
    public List<ReceiverView> findReceivers(List<UUID> receiverIds) {
        if (receiverIds == null || receiverIds.isEmpty()) {
            return List.of();
        }
        return receiverQuery(RECEIVERS.ID.in(receiverIds), RECEIVERS.ID.asc(), receiverIds.size());
    }

    @Override
    public List<SuppressionView> listSuppressions(String afterNormalizedAddress, int limit) {
        Condition where = DSL.noCondition();
        if (afterNormalizedAddress != null && !afterNormalizedAddress.isBlank()) {
            where = where.and(SUPPRESSIONS.EMAIL_NORMALIZED.gt(afterNormalizedAddress));
        }
        return dsl.select(
                        SUPPRESSIONS.EMAIL_NORMALIZED, SUPPRESSIONS.SCOPE, SUPPRESSIONS.REASON,
                        SUPPRESSIONS.CREATED_AT)
                .from(SUPPRESSIONS)
                .where(where)
                .orderBy(SUPPRESSIONS.EMAIL_NORMALIZED.asc())
                .limit(limit)
                .fetch(row -> new SuppressionView(
                        row.value1(),
                        SuppressionScope.valueOf(row.value2()),
                        SuppressionReason.valueOf(row.value3()),
                        row.value4()));
    }

    private List<ReceiverView> receiverQuery(Condition where, SortField<?> order, int limit) {
        return dsl.select(
                        RECEIVERS.ID, RECEIVERS.EMAIL, RECEIVERS.FIRST_NAME, RECEIVERS.LAST_NAME,
                        RECEIVERS.ATTRIBUTES, RECEIVERS.STATUS, RECEIVERS.VERSION,
                        RECEIVERS.CREATED_AT, RECEIVERS.UPDATED_AT)
                .from(RECEIVERS)
                .where(where)
                .orderBy(order)
                .limit(limit)
                .fetch(this::toView);
    }

    private ReceiverView toView(Record row) {
        return new ReceiverView(
                row.get(RECEIVERS.ID),
                row.get(RECEIVERS.EMAIL),
                row.get(RECEIVERS.FIRST_NAME),
                row.get(RECEIVERS.LAST_NAME),
                json.readValue(row.get(RECEIVERS.ATTRIBUTES).data(), ATTRIBUTES),
                ReceiverStatus.valueOf(row.get(RECEIVERS.STATUS)),
                row.get(RECEIVERS.VERSION),
                row.get(RECEIVERS.CREATED_AT),
                row.get(RECEIVERS.UPDATED_AT));
    }
}
