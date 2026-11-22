package dev.ngb.booking.catalog.application.group.service;

import dev.ngb.booking.catalog.application.catalog.exception.CatalogValidationException;
import dev.ngb.booking.catalog.domain.group.GroupMember;
import dev.ngb.booking.catalog.domain.group.GroupStrategy;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** The rules a group body must satisfy, so a use case decides with validated values and one message per violation. */
public final class GroupRules {

    private static final int MIN_MAX_COMBINE = 1;
    private static final int MAX_MAX_COMBINE = 20;

    private GroupRules() {}

    /** @return the Tenant's code, trimmed, which is between 1 and 60 characters */
    public static String code(String raw) {
        String code = raw == null ? null : raw.strip();
        if (code == null || code.isEmpty() || code.length() > 60) {
            throw new CatalogValidationException("code must be between 1 and 60 characters");
        }
        return code;
    }

    /** @return the name, trimmed, which is between 1 and 200 characters */
    public static String name(String raw) {
        String name = raw == null ? null : raw.strip();
        if (name == null || name.isEmpty() || name.length() > 200) {
            throw new CatalogValidationException("name must be between 1 and 200 characters");
        }
        return name;
    }

    public static GroupStrategy strategy(GroupStrategy strategy) {
        if (strategy == null) {
            throw new CatalogValidationException("strategy is required");
        }
        return strategy;
    }

    /** @return the combine cap, defaulting to one, which is between 1 and 20 */
    public static int maxCombine(Integer raw) {
        int maxCombine = raw == null ? MIN_MAX_COMBINE : raw;
        if (maxCombine < MIN_MAX_COMBINE || maxCombine > MAX_MAX_COMBINE) {
            throw new CatalogValidationException("maxCombine must be between 1 and 20");
        }
        return maxCombine;
    }

    /** One member as a caller sent it: a resource the group must hold and its priority, defaulting to zero. */
    public static GroupMember member(UUID resourceId, Integer priority) {
        if (resourceId == null) {
            throw new CatalogValidationException("a group member needs a resourceId");
        }
        int resolved = priority == null ? 0 : priority;
        if (resolved < 0) {
            throw new CatalogValidationException("priority must be at least 0");
        }
        return new GroupMember(resourceId, resolved);
    }

    /** @return the members, a duplicate resource refused, because a group holds a resource at most once */
    public static List<GroupMember> members(List<GroupMember> raw) {
        List<GroupMember> members = raw == null ? List.of() : List.copyOf(raw);
        Set<UUID> distinct = new LinkedHashSet<>();
        for (GroupMember member : members) {
            if (!distinct.add(member.resourceId())) {
                throw new CatalogValidationException(
                        "a group names the resource " + member.resourceId() + " twice");
            }
        }
        return members;
    }

    /** @return the resource ids of the members, in the order they were sent */
    public static List<UUID> resourceIds(List<GroupMember> members) {
        return members.stream().map(GroupMember::resourceId).distinct().toList();
    }
}
