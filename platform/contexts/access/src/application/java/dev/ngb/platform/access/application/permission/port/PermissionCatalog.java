package dev.ngb.platform.access.application.permission.port;

import dev.ngb.platform.access.domain.permission.CataloguedPermission;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import dev.ngb.core.security.PermissionScope;

/** The suite's one permission catalog, assembled from contracts/permissions. */
public interface PermissionCatalog {

    Optional<CataloguedPermission> find(String code);

    List<CataloguedPermission> all();

    Set<String> codesValidIn(PermissionScope scope);
}
