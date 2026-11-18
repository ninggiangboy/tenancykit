package dev.ngb.platform.access.api.user;

import java.util.List;
import java.util.Set;

/** One user plus every effective permission code, the shape of the user-access replica stream. */
public record UserAccessView(UserView user, Set<String> permissions) {

    public UserAccessView {
        permissions = Set.copyOf(permissions);
    }

    /** The permission list in a stable order. */
    public List<String> sortedPermissions() {
        return List.copyOf(new java.util.TreeSet<>(permissions));
    }
}
