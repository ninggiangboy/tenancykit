package dev.ngb.platform.tenancy.application.uiremote.query;

import java.util.List;

/**
 * One remote in the operator listing. {@code activeVersion} is null until an operator activates one, and {@code versions}
 * holds every registered version, newest first.
 */
public record UiRemoteView(
        String name,
        String appCode,
        String title,
        int navOrder,
        List<String> audiences,
        boolean enabled,
        String activeVersion,
        String createdAt,
        long version,
        List<UiRemoteVersionView> versions) {}
