package dev.ngb.platform.tenancy.application.uiremote.query;

import java.util.List;

/** One registered version as the operator listing shows it. */
public record UiRemoteVersionView(
        String version, String entryPath, String integrity, int sdkMajor, String registeredAt, String registeredBy) {}
