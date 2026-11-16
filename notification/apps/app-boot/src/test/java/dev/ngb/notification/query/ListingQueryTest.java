package dev.ngb.notification.query;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.ngb.core.error.BadRequestException;
import dev.ngb.core.id.TenantId;
import dev.ngb.core.query.Page;
import dev.ngb.core.runtime.TransactionScopeHook;
import dev.ngb.core.runtime.UnitOfWork;
import dev.ngb.core.security.Actor;
import dev.ngb.core.security.ActorType;
import dev.ngb.core.security.CurrentActor;
import dev.ngb.core.security.PermissionScope;
import dev.ngb.core.spring.runtime.SpringUnitOfWork;
import dev.ngb.core.spring.runtime.UuidV7IdGenerator;
import dev.ngb.core.spring.tenancy.ScopedTenantContext;
import dev.ngb.core.tenancy.RequestScope;
import dev.ngb.notification.dispatch.adapter.notification.persistence.JooqDispatchReadRepository;
import dev.ngb.notification.dispatch.adapter.notification.persistence.JooqNotificationReadRepository;
import dev.ngb.notification.dispatch.application.notification.query.DispatchListing;
import dev.ngb.notification.dispatch.application.notification.query.DispatchView;
import dev.ngb.notification.dispatch.application.notification.query.ListDispatchesQuery;
import dev.ngb.notification.dispatch.application.notification.query.ListNotificationsQuery;
import dev.ngb.notification.dispatch.application.notification.query.NotificationListing;
import dev.ngb.notification.dispatch.application.notification.query.NotificationView;
import dev.ngb.notification.support.ListQueries;
import dev.ngb.notification.support.PostgresContainerConfig;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import org.flywaydb.core.Flyway;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * The two migrated listings answer the list-query contract: the inbox is only the caller's own rows, a filter replaces
 * the removed {@code unread} flag, {@code page_info} counts every matching row and hands out the cursor, and a parameter
 * the contract does not know is refused rather than ignored.
 *
 * <p>The connection is the unprivileged {@code notification_app} role, so the tenant scope below is enforced twice: by
 * the condition the adapter applies and by row-level security.
 */
class ListingQueryTest {

    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(PostgresContainerConfig.IMAGE);

    static {
        POSTGRES.start();
    }

    private static final TenantId TENANT = new TenantId(UUID.randomUUID());
    private static final TenantId OTHER_TENANT = new TenantId(UUID.randomUUID());
    private static final UUID USER = UUID.randomUUID();
    private static final UUID OTHER_USER = UUID.randomUUID();

    private DSLContext dsl;
    private UnitOfWork unitOfWork;
    private ScopedTenantContext tenantContext;
    private ListNotificationsQuery inbox;
    private ListDispatchesQuery ledger;

    @BeforeAll
    static void migrate() {
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration")
                .placeholders(Map.of("appDbPassword", PostgresContainerConfig.APP_PASSWORD))
                .load()
                .migrate();
    }

    @BeforeEach
    void setUp() {
        PGSimpleDataSource application = new PGSimpleDataSource();
        application.setUrl(POSTGRES.getJdbcUrl());
        application.setUser(PostgresContainerConfig.APP_ROLE);
        application.setPassword(PostgresContainerConfig.APP_PASSWORD);
        dsl = DSL.using(new TransactionAwareDataSourceProxy(application), SQLDialect.POSTGRES);

        tenantContext = new ScopedTenantContext();
        unitOfWork = new SpringUnitOfWork(new DataSourceTransactionManager(application), tenantHook());
        CurrentActor currentActor = () -> new Actor(ActorType.USER, USER, TENANT, PermissionScope.TENANT, "Test User", Set.of());

        inbox = new ListNotificationsQuery(unitOfWork, new JooqNotificationReadRepository(dsl), tenantContext, currentActor);
        ledger = new ListDispatchesQuery(unitOfWork, new JooqDispatchReadRepository(dsl), tenantContext);

        seedEmpty();
    }

    @Test
    void theInboxIsOnlyTheCallersOwnRows() {
        seedInbox();

        List<NotificationView> rows = asTenant(TENANT, () -> inbox.list(ListQueries.of(NotificationListing.SCHEMA, "limit", "50")).data());

        assertThat(rows).extracting(NotificationView::title)
                .as("another user's row and another tenant's row are never listed")
                .containsExactly("read", "newest unread", "oldest unread");
    }

    @Test
    void anUnreadInboxIsTheIsNullFilterOnReadAt() {
        seedInbox();

        List<NotificationView> unread = asTenant(TENANT, () -> inbox.list(ListQueries.of(
                NotificationListing.SCHEMA, "filter[read_at][is_null]", "true", "limit", "50")).data());

        assertThat(unread).extracting(NotificationView::title).containsExactly("newest unread", "oldest unread");
        assertThat(unread).allSatisfy(row -> assertThat(row.readAt()).isNull());
    }

    @Test
    void pageInfoCountsEveryMatchingRowAndTheCursorReadsOn() {
        seedInbox();

        Page<NotificationView> first = asTenant(TENANT, () -> inbox.list(ListQueries.of(
                NotificationListing.SCHEMA, "limit", "2", "page_info", "true")));

        assertThat(first.data()).extracting(NotificationView::title).containsExactly("read", "newest unread");
        assertThat(first.page()).isNotNull();
        assertThat(first.page().total()).as("the total counts every row the filter matches, not the page").isEqualTo(3);
        assertThat(first.page().limit()).isEqualTo(2);
        assertThat(first.page().offset()).isNull();
        assertThat(first.page().nextCursor()).isNotNull();

        Page<NotificationView> second = asTenant(TENANT, () -> inbox.list(ListQueries.of(
                NotificationListing.SCHEMA, "limit", "2", "page_info", "true", "cursor", first.page().nextCursor())));

        assertThat(second.data()).extracting(NotificationView::title).containsExactly("oldest unread");
        assertThat(second.page().next_cursor()).as("the last page has no next cursor").isNull();
        assertThat(second.page().total()).isEqualTo(3);
    }

    @Test
    void pageInfoCursorHandsOutTheCursorWithoutCounting() {
        seedInbox();

        Page<NotificationView> first = asTenant(TENANT, () -> inbox.list(ListQueries.of(
                NotificationListing.SCHEMA, "limit", "2", "page_info", "cursor")));

        assertThat(first.data()).extracting(NotificationView::title).containsExactly("read", "newest unread");
        assertThat(first.page().total()).as("a cursor-only page does not count the matching rows").isNull();
        assertThat(first.page().nextCursor()).isNotNull();

        Page<NotificationView> second = asTenant(TENANT, () -> inbox.list(ListQueries.of(
                NotificationListing.SCHEMA, "limit", "2", "page_info", "cursor", "cursor", first.page().nextCursor())));

        assertThat(second.data()).extracting(NotificationView::title).containsExactly("oldest unread");
        assertThat(second.page().total()).isNull();
        assertThat(second.page().nextCursor()).as("the last page has no next cursor").isNull();
    }

    @Test
    void aRemovedParameterIsRefusedRatherThanIgnored() {
        assertThatThrownBy(() -> ListQueries.of(NotificationListing.SCHEMA, "before", UUID.randomUUID().toString()))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("before")
                .hasMessageContaining("not supported");
        assertThatThrownBy(() -> ListQueries.of(NotificationListing.SCHEMA, "unread", "true"))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("unread");
    }

    @Test
    void theLedgerFiltersByStatusAndSearchesTheRecipient() {
        seedLedger();

        List<DispatchView> dead = asTenant(TENANT, () -> ledger.list(ListQueries.of(
                DispatchListing.SCHEMA, "filter[status][eq]", "DEAD", "limit", "50")).data());
        List<DispatchView> alice = asTenant(TENANT, () -> ledger.list(ListQueries.of(
                DispatchListing.SCHEMA, "filter[recipient][contains]", "ALICE", "limit", "50")).data());
        List<DispatchView> both = asTenant(TENANT, () -> ledger.list(ListQueries.of(
                DispatchListing.SCHEMA,
                "filter[status][in]", "DEAD,PUBLISHED",
                "filter[recipient][contains]", "bob",
                "limit", "50")).data());

        assertThat(dead).extracting(DispatchView::recipient).containsExactly("bob@example.org", "alice@example.com");
        assertThat(alice).extracting(DispatchView::recipient).as("contains is case-insensitive").hasSize(2);
        assertThat(both).extracting(DispatchView::recipient).containsExactly("bob@example.org");
    }

    @Test
    void aSortOnANonSortableFieldIsRefused() {
        assertThatThrownBy(() -> ListQueries.of(DispatchListing.SCHEMA, "sort", "recipient"))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("recipient")
                .hasMessageContaining("cannot be sorted");
    }

    private void seedEmpty() {
        for (TenantId tenant : List.of(TENANT, OTHER_TENANT)) {
            seed(tenant, () -> {
                dsl.execute("delete from notifications");
                dsl.execute("delete from email_dispatches");
            });
        }
    }

    /** Oldest unread, newest unread and one read row for the caller, plus a row of another user and another tenant. */
    private void seedInbox() {
        notification(TENANT, USER, "oldest unread", "2026-11-16T10:00:00Z", null);
        notification(TENANT, USER, "newest unread", "2026-11-16T11:00:00Z", null);
        notification(TENANT, USER, "read", "2026-11-16T12:00:00Z", "2026-11-16T12:30:00Z");
        notification(TENANT, OTHER_USER, "another user", "2026-11-16T13:00:00Z", null);
        notification(OTHER_TENANT, USER, "another tenant", "2026-11-16T14:00:00Z", null);
    }

    private void notification(TenantId tenant, UUID user, String title, String createdAt, String readAt) {
        seed(tenant, () -> dsl.execute(
                """
                insert into notifications (id, tenant_id, user_id, type, source_event_id, source_event_type,
                                           template_key, template_version, locale, title, body, link, read_at, created_at)
                values (?, ?, ?, 'ACCESS_GRANTED', ?, 'user.changed', 'access-granted', 0, 'en', ?, 'Body', null,
                        cast(? as timestamptz), cast(? as timestamptz))
                """,
                idAt(createdAt),
                tenant.value(),
                user,
                UUID.randomUUID(),
                title,
                instant(readAt),
                instant(createdAt)));
    }

    private void seedLedger() {
        dispatch("PUBLISHED", "alice@example.com", "2026-11-16T10:00:00Z");
        dispatch("DEAD", "alice@example.com", "2026-11-16T11:00:00Z");
        dispatch("DEAD", "bob@example.org", "2026-11-16T12:00:00Z");
    }

    private void dispatch(String status, String recipient, String createdAt) {
        seed(TENANT, () -> dsl.execute(
                """
                insert into email_dispatches (id, tenant_id, source_event_id, notification_type, user_id, recipient,
                                              request_id, from_key, template_key, template_version, locale, subject,
                                              status, reason, created_at, published_at)
                values (?, ?, ?, 'ACCESS_GRANTED', ?, ?, ?, 'noreply', 'access-granted', 0, 'en', 'Subject', ?, null,
                        cast(? as timestamptz), null)
                """,
                idAt(createdAt),
                TENANT.value(),
                UUID.randomUUID(),
                USER,
                recipient,
                UUID.randomUUID().toString(),
                status,
                instant(createdAt)));
    }

    /** A UUID v7 whose timestamp is {@code at}, so the id order is the creation order the listing sorts on. */
    private static UUID idAt(String at) {
        Instant instant = Instant.parse(at);
        return new UuidV7IdGenerator(() -> instant).newId();
    }

    private static OffsetDateTime instant(String at) {
        return at == null ? null : OffsetDateTime.ofInstant(Instant.parse(at), ZoneOffset.UTC);
    }

    private void seed(TenantId tenant, Runnable work) {
        tenantContext.callAs(RequestScope.tenant(tenant), () -> unitOfWork.transactional(() -> {
            work.run();
            return (Object) null;
        }));
    }

    private <T> T asTenant(TenantId tenant, Supplier<T> work) {
        return tenantContext.callAs(RequestScope.tenant(tenant), () -> unitOfWork.transactional(work::get));
    }

    /** The production hook's job: the transaction tells PostgreSQL which tenant it belongs to. */
    private TransactionScopeHook tenantHook() {
        return new TransactionScopeHook() {
            @Override
            public RequestScope currentScope() {
                return tenantContext.current();
            }

            @Override
            public void onNewTransaction(RequestScope scope) {
                dsl.execute("select set_config('app.tenant_id', ?, true)", scope.dataTenantId().toString());
            }
        };
    }
}
