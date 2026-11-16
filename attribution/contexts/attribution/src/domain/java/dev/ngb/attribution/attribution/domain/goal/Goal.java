package dev.ngb.attribution.attribution.domain.goal;

import dev.ngb.core.error.BadRequestException;
import dev.ngb.core.error.ConflictException;
import dev.ngb.core.id.TenantId;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * A conversion kind a Tenant reports, such as {@code signup} or {@code purchase}: a stable key, a display name, an
 * optional default value and an optional default currency. A snippet goal names the key, and a postback does too, so the
 * key is what a Tenant's own code writes and the name is what a report shows.
 */
public record Goal(
        UUID id,
        TenantId tenantId,
        String key,
        String name,
        BigDecimal defaultValue,
        String currency,
        GoalStatus status,
        long version,
        Instant createdAt,
        Instant updatedAt) {

    public static final int MAX_NAME = 200;
    public static final BigDecimal MAX_VALUE = new BigDecimal("999999999999999.9999");

    private static final Pattern KEY = Pattern.compile("^[a-z0-9_-]{1,64}$");
    private static final Pattern CURRENCY = Pattern.compile("^[A-Z]{3}$");

    public Goal {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
        key = requireKey(key);
        name = requireName(name);
        currency = requireCurrency(currency);
        if (defaultValue != null) {
            if (defaultValue.signum() < 0) {
                throw new BadRequestException("A goal's default value is not negative.");
            }
            if (defaultValue.compareTo(MAX_VALUE) > 0) {
                throw new BadRequestException("A goal's default value is too large.");
            }
        }
    }

    public static Goal create(
            UUID id,
            TenantId tenantId,
            String key,
            String name,
            BigDecimal defaultValue,
            String currency,
            Instant now) {
        return new Goal(id, tenantId, key, name, defaultValue, currency, GoalStatus.ACTIVE, 1, now, now);
    }

    public Goal update(String name, BigDecimal defaultValue, String currency, Instant now) {
        if (status == GoalStatus.ARCHIVED) {
            throw new ConflictException("An archived goal can no longer be edited.");
        }
        return new Goal(id, tenantId, key, name, defaultValue, currency, status, version + 1, createdAt, now);
    }

    public Goal archive(Instant now) {
        return new Goal(id, tenantId, key, name, defaultValue, currency, GoalStatus.ARCHIVED, version + 1, createdAt, now);
    }

    private static String requireKey(String value) {
        if (value == null || !KEY.matcher(value.strip().toLowerCase(Locale.ROOT)).matches()) {
            throw new BadRequestException("A goal key is 1 to 64 characters of lower-case letters, digits, '_' or '-'.");
        }
        return value.strip().toLowerCase(Locale.ROOT);
    }

    private static String requireName(String value) {
        if (value == null || value.isBlank()) {
            throw new BadRequestException("A goal needs a name.");
        }
        String name = value.strip();
        if (name.length() > MAX_NAME) {
            throw new BadRequestException("A goal name is at most " + MAX_NAME + " characters.");
        }
        return name;
    }

    private static String requireCurrency(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String currency = value.strip().toUpperCase(Locale.ROOT);
        if (!CURRENCY.matcher(currency).matches()) {
            throw new BadRequestException("A goal currency is a three-letter ISO 4217 code.");
        }
        return currency;
    }
}
