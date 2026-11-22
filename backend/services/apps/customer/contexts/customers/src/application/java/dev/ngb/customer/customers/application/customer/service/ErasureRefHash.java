package dev.ngb.customer.customers.application.customer.service;

import dev.ngb.core.id.TenantId;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * The key an erasure record is stored under: {@code sha256(tenant_id ‖ external_ref)}, lower-case hex.
 *
 * <p>The reference is the one value that ties a customer to a person in the Tenant's own system, so an erasure record
 * cannot keep it. Hashing it keeps what a retry needs — the same tenant's same reference maps to the same key — while
 * the stored value stops being the reference. The tenant id is fixed length, so the concatenation cannot be ambiguous
 * between two tenants' references.
 */
public final class ErasureRefHash {

    private ErasureRefHash() {}

    public static String of(TenantId tenantId, String externalRef) {
        MessageDigest sha256;
        try {
            sha256 = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available in this runtime", e);
        }
        byte[] digest = sha256.digest((tenantId.toString() + externalRef).getBytes(StandardCharsets.UTF_8));
        return HexFormat.of().formatHex(digest);
    }
}
