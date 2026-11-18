package dev.ngb.platform.tenancy.application.uiremote.query;

import dev.ngb.core.query.ListQuery;
import dev.ngb.core.query.Page;
import dev.ngb.platform.tenancy.domain.uiremote.RemoteAudience;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Read side of the registry: the operator's listing, and the feed one shell load reads. */
public interface UiRemoteReadRepository {

    /** One page, filtered, sorted and paged by the list-query contract; the versions come in the same call. */
    Page<UiRemoteView> list(ListQuery query);

    Optional<UiRemoteView> find(String name);

    /** Every version of the named remotes in one query, so a page never reads a row at a time. */
    Map<String, List<UiRemoteVersionView>> versionsOf(List<String> remoteNames);

    /**
     * The remotes one shell may mount: enabled, with an active version, listing the caller's audience and — for a
     * tenant caller — whose app the tenant has enabled, unless the remote is the platform console itself.
     *
     * @param tenantId the caller's tenant, read only when {@code checkEntitlements} is true
     */
    List<ConsoleRemoteView> feed(RemoteAudience audience, UUID tenantId, boolean checkEntitlements);
}
