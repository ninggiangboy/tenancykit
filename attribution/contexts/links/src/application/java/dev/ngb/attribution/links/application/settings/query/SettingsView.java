package dev.ngb.attribution.links.application.settings.query;

import java.util.List;

/**
 * One tenant's attribution settings: the click parameter, the origins allowed to collect, and the default window.
 *
 * <p>A tenant that never changed a setting has no row, so this is the merged view of its row (if any) and the
 * deployment defaults. That keeps one shape for the API and the redirect, and lets a new default reach a tenant that
 * never overrode it.
 */
public record SettingsView(String clickParam, List<String> allowedOrigins, int defaultWindowDays) {

    public SettingsView {
        allowedOrigins = allowedOrigins == null ? List.of() : List.copyOf(allowedOrigins);
    }
}
