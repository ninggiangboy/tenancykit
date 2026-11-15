package dev.ngb.email.shared;

import java.util.Set;

/** The email app's permission codes; a test asserts this set equals {@code contracts/permissions/email.yaml}. */
public final class EmailPermissions {

    public static final String TEMPLATE_READ = "email:template:read";
    public static final String TEMPLATE_MANAGE = "email:template:manage";
    public static final String RECEIVER_READ = "email:receiver:read";
    public static final String RECEIVER_MANAGE = "email:receiver:manage";
    public static final String EMAIL_SEND = "email:email:send";
    public static final String EMAIL_READ = "email:email:read";
    public static final String CAMPAIGN_READ = "email:campaign:read";
    public static final String CAMPAIGN_MANAGE = "email:campaign:manage";
    public static final String SETTINGS_MANAGE = "email:settings:manage";

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

    private EmailPermissions() {}
}
