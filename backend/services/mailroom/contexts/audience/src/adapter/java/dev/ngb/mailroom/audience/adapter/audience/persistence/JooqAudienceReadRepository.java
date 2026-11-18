package dev.ngb.mailroom.audience.adapter.audience.persistence;

import static dev.ngb.mailroom_jooq.Tables.GROUP_MEMBERS;
import static dev.ngb.mailroom_jooq.Tables.RECEIVERS;
import static dev.ngb.mailroom_jooq.Tables.RECEIVER_GROUPS;
import static dev.ngb.mailroom_jooq.Tables.SUPPRESSIONS;
import static org.jooq.impl.DSL.noCondition;
import static org.jooq.impl.DSL.select;

import dev.ngb.core.query.ListQuery;
import dev.ngb.core.query.Page;
import dev.ngb.core.spring.query.JooqLists;
import dev.ngb.core.spring.query.ListBinding;
import dev.ngb.mailroom.audience.application.audience.query.AudienceReadRepository;
import dev.ngb.mailroom.audience.application.audience.query.GroupListing;
import dev.ngb.mailroom.audience.application.audience.query.GroupView;
import dev.ngb.mailroom.audience.application.audience.query.ReceiverListing;
import dev.ngb.mailroom.audience.application.audience.query.ReceiverView;
import dev.ngb.mailroom.audience.application.audience.query.SuppressionListing;
import dev.ngb.mailroom.audience.application.audience.query.SuppressionView;
import dev.ngb.mailroom.audience.domain.receiver.ReceiverStatus;
import dev.ngb.mailroom.audience.domain.suppression.SuppressionReason;
import dev.ngb.mailroom.audience.domain.suppression.SuppressionScope;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jooq.DSLContext;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/** The audience read side: receivers, groups, memberships and suppressions. */
public class JooqAudienceReadRepository implements AudienceReadRepository {

    private static final TypeReference<Map<String, Object>> ATTRIBUTES = new TypeReference<>() {};

    /** The receiver fields a caller may filter and sort on, bound to their columns. */
    private static final ListBinding RECEIVER_BINDING = ListBinding.of(ReceiverListing.SCHEMA)
            .column("id", RECEIVERS.ID)
            .column("email", RECEIVERS.EMAIL)
            .column("first_name", RECEIVERS.FIRST_NAME)
            .column("last_name", RECEIVERS.LAST_NAME)
            .column("status", RECEIVERS.STATUS)
            .column("version", RECEIVERS.VERSION)
            .column("created_at", RECEIVERS.CREATED_AT)
            .column("updated_at", RECEIVERS.UPDATED_AT)
            .build();

    /** The group fields a caller may filter and sort on, bound to their columns. */
    private static final ListBinding GROUP_BINDING = ListBinding.of(GroupListing.SCHEMA)
            .column("id", RECEIVER_GROUPS.ID)
            .column("name", RECEIVER_GROUPS.NAME)
            .column("version", RECEIVER_GROUPS.VERSION)
            .column("created_at", RECEIVER_GROUPS.CREATED_AT)
            .column("updated_at", RECEIVER_GROUPS.UPDATED_AT)
            .build();

    /** The suppression fields a caller may filter and sort on, bound to their columns. */
    private static final ListBinding SUPPRESSION_BINDING = ListBinding.of(SuppressionListing.SCHEMA)
            .column("email_normalized", SUPPRESSIONS.EMAIL_NORMALIZED)
            .column("scope", SUPPRESSIONS.SCOPE)
            .column("reason", SUPPRESSIONS.REASON)
            .column("created_at", SUPPRESSIONS.CREATED_AT)
            .build();

    private final DSLContext dsl;
    private final JsonMapper json;

    /** @return the bindings of the audience listings, for the test that checks every sortable field has an index */
    public static List<ListBinding> listBindings() {
        return List.of(RECEIVER_BINDING, GROUP_BINDING, SUPPRESSION_BINDING);
    }

    public JooqAudienceReadRepository(DSLContext dsl, JsonMapper json) {
        this.dsl = dsl;
        this.json = json;
    }

    @Override
    public Page<ReceiverView> listReceivers(ListQuery query) {
        return JooqLists.fetch(dsl, RECEIVERS, noCondition(), RECEIVER_BINDING, query, this::toView);
    }

    @Override
    public Optional<ReceiverView> findReceiver(UUID receiverId) {
        return dsl.selectFrom(RECEIVERS)
                .where(RECEIVERS.ID.eq(receiverId))
                .fetchOptional(this::toView);
    }

    @Override
    public Page<GroupView> listGroups(ListQuery query) {
        return JooqLists.fetch(dsl, RECEIVER_GROUPS, noCondition(), GROUP_BINDING, query, JooqAudienceReadRepository::toView);
    }

    @Override
    public Optional<GroupView> findGroup(UUID groupId) {
        return dsl.selectFrom(RECEIVER_GROUPS)
                .where(RECEIVER_GROUPS.ID.eq(groupId))
                .fetchOptional(row -> new GroupView(
                        row.getId(), row.getName(), row.getVersion(), row.getCreatedAt(), row.getUpdatedAt()));
    }

    @Override
    public Page<ReceiverView> listGroupReceivers(UUID groupId, ListQuery query) {
        return JooqLists.fetch(dsl, RECEIVERS,
                RECEIVERS.ID.in(select(GROUP_MEMBERS.RECEIVER_ID).from(GROUP_MEMBERS).where(GROUP_MEMBERS.GROUP_ID.eq(groupId))),
                RECEIVER_BINDING, query, this::toView);
    }

    @Override
    public List<ReceiverView> pageGroupReceivers(UUID groupId, UUID afterReceiverId, int limit) {
        var where = RECEIVERS.ID.in(
                select(GROUP_MEMBERS.RECEIVER_ID).from(GROUP_MEMBERS).where(GROUP_MEMBERS.GROUP_ID.eq(groupId)));
        if (afterReceiverId != null) {
            where = where.and(RECEIVERS.ID.gt(afterReceiverId));
        }
        return dsl.selectFrom(RECEIVERS)
                .where(where)
                .orderBy(RECEIVERS.ID.asc())
                .limit(limit)
                .fetch(this::toView);
    }

    @Override
    public List<ReceiverView> findReceivers(List<UUID> receiverIds) {
        if (receiverIds == null || receiverIds.isEmpty()) {
            return List.of();
        }
        return dsl.selectFrom(RECEIVERS)
                .where(RECEIVERS.ID.in(receiverIds))
                .orderBy(RECEIVERS.ID.asc())
                .limit(receiverIds.size())
                .fetch(this::toView);
    }

    @Override
    public Page<SuppressionView> listSuppressions(ListQuery query) {
        return JooqLists.fetch(dsl, SUPPRESSIONS, noCondition(), SUPPRESSION_BINDING, query,
                JooqAudienceReadRepository::toView);
    }

    private ReceiverView toView(dev.ngb.mailroom_jooq.tables.records.ReceiversRecord row) {
        return new ReceiverView(
                row.getId(),
                row.getEmail(),
                row.getFirstName(),
                row.getLastName(),
                json.readValue(row.getAttributes().data(), ATTRIBUTES),
                ReceiverStatus.valueOf(row.getStatus()),
                row.getVersion(),
                row.getCreatedAt(),
                row.getUpdatedAt());
    }

    private static GroupView toView(dev.ngb.mailroom_jooq.tables.records.ReceiverGroupsRecord row) {
        return new GroupView(row.getId(), row.getName(), row.getVersion(), row.getCreatedAt(), row.getUpdatedAt());
    }

    private static SuppressionView toView(dev.ngb.mailroom_jooq.tables.records.SuppressionsRecord row) {
        return new SuppressionView(
                row.getEmailNormalized(),
                SuppressionScope.valueOf(row.getScope()),
                SuppressionReason.valueOf(row.getReason()),
                row.getCreatedAt());
    }
}
