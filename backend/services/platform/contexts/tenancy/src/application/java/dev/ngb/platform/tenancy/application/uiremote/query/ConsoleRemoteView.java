package dev.ngb.platform.tenancy.application.uiremote.query;

/**
 * One remote as a shell receives it: only what it needs to verify, register and mount, never the whole version list.
 */
public record ConsoleRemoteView(
        String name, String title, String appCode, int navOrder, String version, String entryPath, String integrity,
        int sdkMajor) {}
