package dev.ngb.crm.shared;

import java.util.Set;

/** The crm app's permission codes; a test asserts this set equals {@code contracts/permissions/crm.yaml}. */
public final class CrmPermissions {

    public static final String CUSTOMER_READ = "crm:customer:read";
    public static final String CUSTOMER_MANAGE = "crm:customer:manage";
    public static final String CUSTOMER_EXPORT = "crm:customer:export";
    public static final String CUSTOMER_ERASE = "crm:customer:erase";
    public static final String FIELD_MANAGE = "crm:field:manage";
    public static final String ORDER_READ = "crm:order:read";
    public static final String ORDER_RECORD = "crm:order:record";
    public static final String ACTIVITY_READ = "crm:activity:read";
    public static final String ACTIVITY_RECORD = "crm:activity:record";
    public static final String WEBHOOK_MANAGE = "crm:webhook:manage";

    public static final Set<String> CODES = Set.of(
            CUSTOMER_READ,
            CUSTOMER_MANAGE,
            CUSTOMER_EXPORT,
            CUSTOMER_ERASE,
            FIELD_MANAGE,
            ORDER_READ,
            ORDER_RECORD,
            ACTIVITY_READ,
            ACTIVITY_RECORD,
            WEBHOOK_MANAGE);

    private CrmPermissions() {}
}
