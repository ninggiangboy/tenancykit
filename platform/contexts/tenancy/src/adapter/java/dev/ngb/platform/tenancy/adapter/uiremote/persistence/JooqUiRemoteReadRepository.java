package dev.ngb.platform.tenancy.adapter.uiremote.persistence;

import static dev.ngb.platform_jooq.Tables.APP_ENTITLEMENTS;
import static dev.ngb.platform_jooq.Tables.UI_REMOTES;
import static dev.ngb.platform_jooq.Tables.UI_REMOTE_VERSIONS;

import dev.ngb.core.query.ListQuery;
import dev.ngb.core.query.Page;
import dev.ngb.core.spring.query.JooqLists;
import dev.ngb.core.spring.query.ListBinding;
import dev.ngb.platform.tenancy.application.uiremote.query.UiRemoteReadRepository;
import dev.ngb.platform.tenancy.application.uiremote.query.ConsoleRemoteView;
import dev.ngb.platform.tenancy.application.uiremote.query.UiRemoteListing;
import dev.ngb.platform.tenancy.application.uiremote.query.UiRemoteVersionView;
import dev.ngb.platform.tenancy.application.uiremote.query.UiRemoteView;
import dev.ngb.platform.tenancy.domain.uiremote.RemoteAudience;
import dev.ngb.platform_jooq.tables.records.UiRemoteVersionsRecord;
import dev.ngb.platform_jooq.tables.records.UiRemotesRecord;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;

/** The read side of the registry: the operator listing, and the one query a shell's load costs. */
public class JooqUiRemoteReadRepository implements UiRemoteReadRepository {

    /** The console itself is every tenant's admin surface, so its remote needs no entitlement. */
    private static final String PLATFORM_APP = "platform";

    /** The fields a caller may filter and sort on, bound to their columns; every value is a bind variable. */
    private static final ListBinding BINDING = ListBinding.of(UiRemoteListing.SCHEMA)
            .column("name", UI_REMOTES.NAME)
            .column("app_code", UI_REMOTES.APP_CODE)
            .column("nav_order", UI_REMOTES.NAV_ORDER)
            .column("enabled", UI_REMOTES.ENABLED)
            .column("created_at", UI_REMOTES.CREATED_AT)
            .column("version", UI_REMOTES.VERSION)
            .build();

    private final DSLContext dsl;

    /** @return the binding of the remote listing, for the test that checks every sortable field has an index */
    public static ListBinding listBinding() {
        return BINDING;
    }

    public JooqUiRemoteReadRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    @Override
    public Page<UiRemoteView> list(ListQuery query) {
        return JooqLists.fetchPage(dsl, UI_REMOTES, DSL.noCondition(), BINDING, query, rows -> {
            Map<String, List<UiRemoteVersionView>> versions = versionsOf(names(rows));
            List<UiRemoteView> views = new ArrayList<>(rows.size());
            for (UiRemotesRecord row : rows) {
                views.add(toView(row, versions.getOrDefault(row.getName(), List.of())));
            }
            return views;
        });
    }

    @Override
    public Optional<UiRemoteView> find(String name) {
        return dsl.selectFrom(UI_REMOTES)
                .where(UI_REMOTES.NAME.eq(name))
                .fetchOptional()
                .map(row -> toView(row, versionsOf(List.of(name)).getOrDefault(name, List.of())));
    }

    @Override
    public Map<String, List<UiRemoteVersionView>> versionsOf(List<String> remoteNames) {
        if (remoteNames.isEmpty()) {
            return Map.of();
        }
        List<UiRemoteVersionsRecord> rows = dsl.selectFrom(UI_REMOTE_VERSIONS)
                .where(UI_REMOTE_VERSIONS.REMOTE_NAME.in(remoteNames))
                .orderBy(UI_REMOTE_VERSIONS.REMOTE_NAME, UI_REMOTE_VERSIONS.REGISTERED_AT.desc(), UI_REMOTE_VERSIONS.VERSION.desc())
                .fetch();
        Map<String, List<UiRemoteVersionView>> byRemote = new LinkedHashMap<>();
        for (UiRemoteVersionsRecord row : rows) {
            byRemote.computeIfAbsent(row.getRemoteName(), name -> new ArrayList<>()).add(toVersionView(row));
        }
        return byRemote;
    }

    /**
     * One query: the remote joined to the version an operator activated, narrowed to the caller's audience and, for a
     * tenant, to the apps that tenant has enabled. The entitlement subquery runs under the request's tenant context, so
     * row-level security applies to it as well.
     */
    @Override
    public List<ConsoleRemoteView> feed(RemoteAudience audience, UUID tenantId, boolean checkEntitlements) {
        Condition entitled = checkEntitlements
                ? UI_REMOTES.APP_CODE.eq(PLATFORM_APP).or(DSL.exists(dsl.selectOne()
                        .from(APP_ENTITLEMENTS)
                        .where(APP_ENTITLEMENTS.TENANT_ID.eq(tenantId))
                        .and(APP_ENTITLEMENTS.APP_CODE.eq(UI_REMOTES.APP_CODE))
                        .and(APP_ENTITLEMENTS.ENABLED.isTrue())))
                : DSL.noCondition();
        return dsl.select(
                        UI_REMOTES.NAME,
                        UI_REMOTES.TITLE,
                        UI_REMOTES.APP_CODE,
                        UI_REMOTES.NAV_ORDER,
                        UI_REMOTE_VERSIONS.VERSION,
                        UI_REMOTE_VERSIONS.ENTRY_PATH,
                        UI_REMOTE_VERSIONS.INTEGRITY,
                        UI_REMOTE_VERSIONS.SDK_MAJOR)
                .from(UI_REMOTES)
                .join(UI_REMOTE_VERSIONS)
                .on(
                        UI_REMOTE_VERSIONS.REMOTE_NAME.eq(UI_REMOTES.NAME),
                        UI_REMOTE_VERSIONS.VERSION.eq(UI_REMOTES.ACTIVE_VERSION))
                .where(UI_REMOTES.ENABLED.isTrue())
                .and(DSL.condition("? = any(" + UI_REMOTES.AUDIENCES + ")", audience.name()))
                .and(entitled)
                .orderBy(UI_REMOTES.NAV_ORDER, UI_REMOTES.NAME)
                .fetch(row -> new ConsoleRemoteView(
                        row.get(UI_REMOTES.NAME),
                        row.get(UI_REMOTES.TITLE),
                        row.get(UI_REMOTES.APP_CODE),
                        row.get(UI_REMOTES.NAV_ORDER),
                        row.get(UI_REMOTE_VERSIONS.VERSION),
                        row.get(UI_REMOTE_VERSIONS.ENTRY_PATH),
                        row.get(UI_REMOTE_VERSIONS.INTEGRITY),
                        row.get(UI_REMOTE_VERSIONS.SDK_MAJOR)));
    }

    private static List<String> names(List<UiRemotesRecord> rows) {
        return rows.stream().map(UiRemotesRecord::getName).toList();
    }

    private static UiRemoteView toView(UiRemotesRecord row, List<UiRemoteVersionView> versions) {
        return new UiRemoteView(
                row.getName(),
                row.getAppCode(),
                row.getTitle(),
                row.getNavOrder(),
                List.of(row.getAudiences()),
                row.getEnabled(),
                row.getActiveVersion(),
                row.getCreatedAt().toString(),
                row.getVersion(),
                versions);
    }

    private static UiRemoteVersionView toVersionView(UiRemoteVersionsRecord row) {
        return new UiRemoteVersionView(
                row.getVersion(),
                row.getEntryPath(),
                row.getIntegrity(),
                row.getSdkMajor(),
                row.getRegisteredAt().toString(),
                row.getRegisteredBy());
    }
}
