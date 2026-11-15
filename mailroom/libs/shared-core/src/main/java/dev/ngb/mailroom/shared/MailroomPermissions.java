package dev.ngb.mailroom.shared;

import java.util.Set;

/** The mailroom app's permission codes; a test asserts this set equals {@code contracts/permissions/mailroom.yaml}. */
public final class MailroomPermissions {

    public static final String TEMPLATE_READ = "mailroom:template:read";
    public static final String TEMPLATE_MANAGE = "mailroom:template:manage";
    public static final String RECEIVER_READ = "mailroom:receiver:read";
    public static final String RECEIVER_MANAGE = "mailroom:receiver:manage";
    public static final String EMAIL_SEND = "mailroom:email:send";
    public static final String EMAIL_READ = "mailroom:email:read";
    public static final String CAMPAIGN_READ = "mailroom:campaign:read";
    public static final String CAMPAIGN_MANAGE = "mailroom:campaign:manage";
    public static final String SETTINGS_MANAGE = "mailroom:settings:manage";

    public static final Set<String> CODES = Set.of(
            TEMPLATE_READ,
            TEMPLATE_MANAGE,
            RECEIVER_READ,
            RECEIVER_MANAGE,
            EMAIL_SEND,
            EMAIL_READ,
            CAMPAIGN_READ,
            CAMPAIGN_MANAGE,
            SETTINGS_MANAGE);

    private MailroomPermissions() {}
}
