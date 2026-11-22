package dev.ngb.attribution.shared;

import java.util.Set;

/** The attribution app's permission codes; a test asserts this set equals {@code contracts/permissions/attribution.yaml}. */
public final class AttributionPermissions {

    public static final String CAMPAIGN_READ = "attribution:campaign:read";
    public static final String CAMPAIGN_MANAGE = "attribution:campaign:manage";
    public static final String LINK_READ = "attribution:link:read";
    public static final String LINK_MANAGE = "attribution:link:manage";
    public static final String GOAL_READ = "attribution:goal:read";
    public static final String GOAL_MANAGE = "attribution:goal:manage";
    public static final String CONVERSION_WRITE = "attribution:conversion:write";
    public static final String REPORT_READ = "attribution:report:read";
    public static final String SPEND_READ = "attribution:spend:read";
    public static final String SPEND_MANAGE = "attribution:spend:manage";
    public static final String CONNECTION_MANAGE = "attribution:connection:manage";
    public static final String SETTINGS_MANAGE = "attribution:settings:manage";
    public static final String DATA_ERASE = "attribution:data:erase";

    public static final Set<String> CODES = Set.of(
            CAMPAIGN_READ,
            CAMPAIGN_MANAGE,
            LINK_READ,
            LINK_MANAGE,
            GOAL_READ,
            GOAL_MANAGE,
            CONVERSION_WRITE,
            REPORT_READ,
            SPEND_READ,
            SPEND_MANAGE,
            CONNECTION_MANAGE,
            SETTINGS_MANAGE,
            DATA_ERASE);

    private AttributionPermissions() {}
}
