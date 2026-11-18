package dev.ngb.platform.tenancy.adapter.uiremote.persistence;

import static dev.ngb.platform_jooq.Tables.UI_REMOTES;
import static dev.ngb.platform_jooq.Tables.UI_REMOTE_VERSIONS;

import dev.ngb.platform.tenancy.application.uiremote.port.UiRemoteRepository;
import dev.ngb.platform.tenancy.domain.uiremote.RemoteAudience;
import dev.ngb.platform.tenancy.domain.uiremote.UiRemote;
import dev.ngb.platform.tenancy.domain.uiremote.UiRemoteVersion;
import dev.ngb.platform_jooq.tables.records.UiRemoteVersionsRecord;
import dev.ngb.platform_jooq.tables.records.UiRemotesRecord;
import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;
import org.jooq.DSLContext;

/** The write side of the registry. A version row is inserted once and never updated. */
public class JooqUiRemoteRepository implements UiRemoteRepository {

    private final DSLContext dsl;

    public JooqUiRemoteRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    @Override
    public Optional<UiRemote> find(String name) {
        return dsl.selectFrom(UI_REMOTES).where(UI_REMOTES.NAME.eq(name)).fetchOptional(JooqUiRemoteRepository::toRemote);
    }

    @Override
    public Optional<UiRemote> lock(String name) {
        return dsl.selectFrom(UI_REMOTES)
                .where(UI_REMOTES.NAME.eq(name))
                .forUpdate()
                .fetchOptional(JooqUiRemoteRepository::toRemote);
    }

    @Override
    public void insert(UiRemote remote) {
        dsl.insertInto(UI_REMOTES)
                .set(UI_REMOTES.NAME, remote.name())
                .set(UI_REMOTES.APP_CODE, remote.appCode())
                .set(UI_REMOTES.TITLE, remote.title())
                .set(UI_REMOTES.NAV_ORDER, remote.navOrder())
                .set(UI_REMOTES.AUDIENCES, audiences(remote.audiences()))
                .set(UI_REMOTES.ENABLED, remote.isEnabled())
                .set(UI_REMOTES.ACTIVE_VERSION, remote.activeVersion())
                .set(UI_REMOTES.CREATED_AT, remote.createdAt())
                .set(UI_REMOTES.VERSION, remote.version())
                .execute();
    }

    @Override
    public void update(UiRemote remote) {
        int updated = dsl.update(UI_REMOTES)
                .set(UI_REMOTES.TITLE, remote.title())
                .set(UI_REMOTES.NAV_ORDER, remote.navOrder())
                .set(UI_REMOTES.ENABLED, remote.isEnabled())
                .set(UI_REMOTES.ACTIVE_VERSION, remote.activeVersion())
                .set(UI_REMOTES.VERSION, remote.version())
                .where(UI_REMOTES.NAME.eq(remote.name()))
                .and(UI_REMOTES.VERSION.lt(remote.version()))
                .execute();
        if (updated != 1) {
            throw new IllegalStateException("remote " + remote.name() + " was not updated: version " + remote.version()
                    + " is not newer");
        }
    }

    @Override
    public Optional<UiRemoteVersion> findVersion(String name, String version) {
        return dsl.selectFrom(UI_REMOTE_VERSIONS)
                .where(UI_REMOTE_VERSIONS.REMOTE_NAME.eq(name))
                .and(UI_REMOTE_VERSIONS.VERSION.eq(version))
                .fetchOptional(JooqUiRemoteRepository::toVersion);
    }

    @Override
    public void insertVersion(UiRemoteVersion version) {
        dsl.insertInto(UI_REMOTE_VERSIONS)
                .set(UI_REMOTE_VERSIONS.REMOTE_NAME, version.remoteName())
                .set(UI_REMOTE_VERSIONS.VERSION, version.version())
                .set(UI_REMOTE_VERSIONS.ENTRY_PATH, version.entryPath())
                .set(UI_REMOTE_VERSIONS.INTEGRITY, version.integrity())
                .set(UI_REMOTE_VERSIONS.SDK_MAJOR, version.sdkMajor())
                .set(UI_REMOTE_VERSIONS.REGISTERED_AT, version.registeredAt())
                .set(UI_REMOTE_VERSIONS.REGISTERED_BY, version.registeredBy())
                .execute();
    }

    private static UiRemote toRemote(UiRemotesRecord record) {
        return UiRemote.restore(
                record.getName(), record.getAppCode(), record.getTitle(), record.getNavOrder(),
                audiences(record.getAudiences()), record.getEnabled(), record.getActiveVersion(), record.getCreatedAt(),
                record.getVersion());
    }

    static UiRemoteVersion toVersion(UiRemoteVersionsRecord record) {
        return UiRemoteVersion.restore(
                record.getRemoteName(), record.getVersion(), record.getEntryPath(), record.getIntegrity(),
                record.getSdkMajor(), record.getRegisteredAt(), record.getRegisteredBy());
    }

    private static Set<RemoteAudience> audiences(String[] stored) {
        EnumSet<RemoteAudience> audiences = EnumSet.noneOf(RemoteAudience.class);
        for (String value : stored) {
            audiences.add(RemoteAudience.parse(value));
        }
        return audiences;
    }

    private static String[] audiences(Set<RemoteAudience> audiences) {
        return audiences.stream().map(RemoteAudience::name).sorted().toArray(String[]::new);
    }
}
