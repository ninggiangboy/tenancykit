package dev.ngb.platform.tenancy.application.uiremote.port;

import dev.ngb.platform.tenancy.domain.uiremote.UiRemote;
import dev.ngb.platform.tenancy.domain.uiremote.UiRemoteVersion;
import java.util.Optional;

/** Write side of the console's UI remote registry. */
public interface UiRemoteRepository {

    Optional<UiRemote> find(String name);

    /** Reads the row under a row lock, so the state change that follows cannot race another one. */
    Optional<UiRemote> lock(String name);

    void insert(UiRemote remote);

    /** Writes the row, but only when its stored version is older than the one on the aggregate. */
    void update(UiRemote remote);

    Optional<UiRemoteVersion> findVersion(String name, String version);

    /** A version is written once; the primary key refuses a second row for the same remote and version. */
    void insertVersion(UiRemoteVersion version);
}
