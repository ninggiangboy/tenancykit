package dev.ngb.customer.shared;

import java.util.Set;

/** The customer app's permission codes; a test asserts this set equals {@code contracts/permissions/customer.yaml}. */
public final class CustomerPermissions {

    public static final String CUSTOMER_READ = "customer:customer:read";
    public static final String CUSTOMER_MANAGE = "customer:customer:manage";
    public static final String CUSTOMER_EXPORT = "customer:customer:export";
    public static final String CUSTOMER_ERASE = "customer:customer:erase";
    public static final String FIELD_MANAGE = "customer:field:manage";
    public static final String ORDER_READ = "customer:order:read";
    public static final String ORDER_RECORD = "customer:order:record";
    public static final String ACTIVITY_READ = "customer:activity:read";
    public static final String ACTIVITY_RECORD = "customer:activity:record";
    public static final String WEBHOOK_MANAGE = "customer:webhook:manage";

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

    private CustomerPermissions() {}
}
