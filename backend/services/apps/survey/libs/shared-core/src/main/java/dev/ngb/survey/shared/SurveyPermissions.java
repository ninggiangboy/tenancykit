package dev.ngb.survey.shared;

import java.util.Set;

/** The survey app's permission codes; a test asserts this set equals {@code contracts/permissions/survey.yaml}. */
public final class SurveyPermissions {

    public static final String SURVEY_READ = "survey:survey:read";
    public static final String SURVEY_MANAGE = "survey:survey:manage";
    public static final String LINK_MANAGE = "survey:link:manage";
    public static final String RESPONSE_READ = "survey:response:read";
    public static final String RESPONSE_DELETE = "survey:response:delete";
    public static final String ANALYTICS_READ = "survey:analytics:read";
    public static final String EXPORT_MANAGE = "survey:export:manage";
    public static final String WEBHOOK_MANAGE = "survey:webhook:manage";
    public static final String SETTINGS_MANAGE = "survey:settings:manage";

    public static final Set<String> CODES = Set.of(
            SURVEY_READ,
            SURVEY_MANAGE,
            LINK_MANAGE,
            RESPONSE_READ,
            RESPONSE_DELETE,
            ANALYTICS_READ,
            EXPORT_MANAGE,
            WEBHOOK_MANAGE,
            SETTINGS_MANAGE);

    private SurveyPermissions() {}
}
