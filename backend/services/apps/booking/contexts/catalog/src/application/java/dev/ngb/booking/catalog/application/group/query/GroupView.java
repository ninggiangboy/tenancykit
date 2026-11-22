package dev.ngb.booking.catalog.application.group.query;

import java.util.List;
import java.util.UUID;

/** One group as a read returns it. The version is what a caller sends back in {@code If-Match} to patch it. */
public record GroupView(
        UUID id,
        String code,
        String name,
        String strategy,
        int maxCombine,
        List<GroupMemberView> members,
        long version) {}
