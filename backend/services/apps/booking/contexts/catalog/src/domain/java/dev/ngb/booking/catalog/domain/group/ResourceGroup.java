package dev.ngb.booking.catalog.domain.group;

import dev.ngb.core.id.TenantId;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * A set of resources one requirement may be answered by: the strategy that picks a member, how many of them a booking
 * may combine, and the members themselves. The code is the Tenant's own, immutable and unique per tenant; the version
 * is the ETag a PATCH pins.
 *
 * <p>The cursor is the round-robin position, so the next assignment does not always start at the first member. It is
 * advanced elsewhere and is not part of the representation a caller writes.
 */
public final class ResourceGroup {

    private static final int MIN_MAX_COMBINE = 1;
    private static final int MAX_MAX_COMBINE = 20;

    private final GroupId id;
    private final TenantId tenantId;
    private final String code;
    private String name;
    private GroupStrategy strategy;
    private int maxCombine;
    private List<GroupMember> members;
    private String cursor;
    private final Instant createdAt;
    private Instant updatedAt;
    private long version;

    private ResourceGroup(
            GroupId id,
            TenantId tenantId,
            String code,
            String name,
            GroupStrategy strategy,
            int maxCombine,
            List<GroupMember> members,
            String cursor,
            Instant createdAt,
            Instant updatedAt,
            long version) {
        this.id = Objects.requireNonNull(id, "id");
        this.tenantId = Objects.requireNonNull(tenantId, "tenantId");
        this.code = Objects.requireNonNull(code, "code");
        this.name = Objects.requireNonNull(name, "name");
        this.strategy = Objects.requireNonNull(strategy, "strategy");
        this.maxCombine = requireMaxCombine(maxCombine);
        this.members = copyOfMembers(members);
        this.cursor = cursor;
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
        this.updatedAt = Objects.requireNonNull(updatedAt, "updatedAt");
        this.version = version;
    }

    /** A group that did not exist until now, with no round-robin position yet. */
    public static ResourceGroup create(
            GroupId id,
            TenantId tenantId,
            String code,
            String name,
            GroupStrategy strategy,
            int maxCombine,
            List<GroupMember> members,
            Instant now) {
        return new ResourceGroup(id, tenantId, code, name, strategy, maxCombine, members, null, now, now, 1);
    }

    /** The group a repository reads back from storage. */
    public static ResourceGroup restore(
            GroupId id,
            TenantId tenantId,
            String code,
            String name,
            GroupStrategy strategy,
            int maxCombine,
            List<GroupMember> members,
            String cursor,
            Instant createdAt,
            Instant updatedAt,
            long version) {
        return new ResourceGroup(
                id, tenantId, code, name, strategy, maxCombine, members, cursor, createdAt, updatedAt, version);
    }

    /**
     * Replaces the name, the strategy, the combine cap and the member set with what the caller sent.
     *
     * @return whether anything changed, because a body identical to what is stored must bump no version
     */
    public boolean change(String newName, GroupStrategy newStrategy, int newMaxCombine, List<GroupMember> newMembers, Instant now) {
        String name = Objects.requireNonNull(newName, "name");
        GroupStrategy strategy = Objects.requireNonNull(newStrategy, "strategy");
        int maxCombine = requireMaxCombine(newMaxCombine);
        List<GroupMember> members = copyOfMembers(newMembers);
        if (this.name.equals(name)
                && this.strategy == strategy
                && this.maxCombine == maxCombine
                && this.members.equals(members)) {
            return false;
        }
        this.name = name;
        this.strategy = strategy;
        this.maxCombine = maxCombine;
        this.members = members;
        this.updatedAt = now;
        this.version++;
        return true;
    }

    public GroupId id() {
        return id;
    }

    public TenantId tenantId() {
        return tenantId;
    }

    public String code() {
        return code;
    }

    public String name() {
        return name;
    }

    public GroupStrategy strategy() {
        return strategy;
    }

    public int maxCombine() {
        return maxCombine;
    }

    /** The group's members, in the order the caller stored them. */
    public List<GroupMember> members() {
        return members;
    }

    public String cursor() {
        return cursor;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant updatedAt() {
        return updatedAt;
    }

    public long version() {
        return version;
    }

    private static int requireMaxCombine(int maxCombine) {
        if (maxCombine < MIN_MAX_COMBINE || maxCombine > MAX_MAX_COMBINE) {
            throw new IllegalArgumentException("maxCombine must be between 1 and 20");
        }
        return maxCombine;
    }

    private static List<GroupMember> copyOfMembers(List<GroupMember> members) {
        List<GroupMember> copy = members == null ? List.of() : List.copyOf(members);
        Set<UUID> distinct = new LinkedHashSet<>();
        for (GroupMember member : copy) {
            if (!distinct.add(member.resourceId())) {
                throw new IllegalArgumentException("a group names the resource " + member.resourceId() + " twice");
            }
        }
        return copy;
    }
}
