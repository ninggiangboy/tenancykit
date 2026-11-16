package dev.ngb.booking.catalog.application.resource.query;

import java.util.UUID;

/**
 * One resource as a read returns it. The version is the ETag, so a caller can patch what it just read without a second
 * call; the code and the zone are the immutable identity of the resource.
 */
public record ResourceView(
        UUID id,
        String code,
        String name,
        String zone,
        Integer seats,
        String status,
        long version) {}
