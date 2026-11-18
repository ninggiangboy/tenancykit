package dev.ngb.loyalty.program.domain.tier;

import dev.ngb.core.id.TenantId;
import java.math.BigDecimal;
import java.util.Objects;

/**
 * One step of the ordered tier list. {@code code} is the identity a caller knows and a member's tier is stored by, so
 * replacing the list keeps the row of every code that is still there.
 *
 * <p>Immutable: a replace builds the new list and the repository writes the difference.
 */
public record Tier(
        TenantId tenantId,
        TierId id,
        String code,
        String name,
        long minSpend,
        BigDecimal earnMultiplier,
        int position) {

    public Tier {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(earnMultiplier, "earnMultiplier");
    }

    /** The same tier with the values a replace asked for, keeping its identity. */
    public Tier moveTo(String newName, long newMinSpend, BigDecimal newEarnMultiplier, int newPosition) {
        return new Tier(tenantId, id, code, newName, newMinSpend, newEarnMultiplier, newPosition);
    }
}
