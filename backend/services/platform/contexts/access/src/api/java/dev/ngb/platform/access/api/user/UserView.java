package dev.ngb.platform.access.api.user;

import java.util.UUID;

/** One user as a reader sees it. */
public record UserView(
        UUID id, UUID tenantId, String scope, String keycloakSubject, String email, String displayName, String status, long version) {}
