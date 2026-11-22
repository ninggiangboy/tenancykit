package dev.ngb.attribution.links.application.settings.query;

import java.util.List;

/** The read side of a tenant's settings. */
public interface SettingsReadRepository {

    SettingsView load(String defaultClickParam, int defaultWindowDays);
}
