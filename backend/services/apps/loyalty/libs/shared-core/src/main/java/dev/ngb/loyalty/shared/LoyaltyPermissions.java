package dev.ngb.loyalty.shared;

import java.util.Set;

/**
 * The loyalty app's permission codes, mirrored from {@code contracts/permissions/loyalty.yaml}; a test asserts this set
 * equals that file. Every code is tenant scope, and every code but {@code loyalty:program:manage} may be granted to a
 * Tenant System credential.
 *
 * <p>Only the codes an endpoint already enforces are annotated; the rest exist here because the catalog is the app's
 * contract with Platform, and later phases enforce them as their endpoints arrive.
 */
public final class LoyaltyPermissions {

    public static final String PROGRAM_READ = "loyalty:program:read";
    public static final String PROGRAM_MANAGE = "loyalty:program:manage";
    public static final String MEMBER_READ = "loyalty:member:read";
    public static final String MEMBER_MANAGE = "loyalty:member:manage";
    public static final String MEMBER_ERASE = "loyalty:member:erase";
    public static final String ORDER_RECORD = "loyalty:order:record";
    public static final String POINTS_REDEEM = "loyalty:points:redeem";
    public static final String REWARD_READ = "loyalty:reward:read";
    public static final String REWARD_MANAGE = "loyalty:reward:manage";
    public static final String REWARD_REDEEM = "loyalty:reward:redeem";
    public static final String VOUCHER_READ = "loyalty:voucher:read";
    public static final String VOUCHER_USE = "loyalty:voucher:use";
    public static final String WEBHOOK_MANAGE = "loyalty:webhook:manage";

    public static final Set<String> CODES = Set.of(
            PROGRAM_READ,
            PROGRAM_MANAGE,
            MEMBER_READ,
            MEMBER_MANAGE,
            MEMBER_ERASE,
            ORDER_RECORD,
            POINTS_REDEEM,
            REWARD_READ,
            REWARD_MANAGE,
            REWARD_REDEEM,
            VOUCHER_READ,
            VOUCHER_USE,
            WEBHOOK_MANAGE);

    private LoyaltyPermissions() {}
}
