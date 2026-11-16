package dev.ngb.attribution.links.domain.campaign;

import dev.ngb.core.error.BadRequestException;
import dev.ngb.core.error.ConflictException;
import dev.ngb.core.id.TenantId;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Currency;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * A campaign: a Tenant's marketing effort with one attribution model, one window, one currency and a slug.
 *
 * <p>Changing the model or the window is recorded here and acted on in a later phase by a recompute run; this class only
 * guarantees the values are legal. The currency is fixed once anything is measured against the campaign, so a change
 * after spend or a conversion exists is refused rather than silently re-labelling money.
 */
public record Campaign(
        UUID id,
        TenantId tenantId,
        String name,
        String slug,
        String channel,
        CampaignStatus status,
        AttributionModel model,
        int windowDays,
        String currency,
        LocalDate startsOn,
        LocalDate endsOn,
        long version,
        Instant createdAt,
        Instant updatedAt) {

    public static final int MAX_NAME = 200;
    public static final int DEFAULT_WINDOW_DAYS = 30;
    public static final int MIN_WINDOW_DAYS = 1;
    public static final int MAX_WINDOW_DAYS = 90;

    private static final Pattern SLUG = Pattern.compile("^[a-z0-9][a-z0-9_-]{2,63}$");

    public Campaign {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(model, "model");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
        name = requireName(name);
        slug = requireSlug(slug);
        channel = channel == null || channel.isBlank() ? null : clip(channel.strip(), 64);
        currency = requireCurrency(currency);
        if (windowDays < MIN_WINDOW_DAYS || windowDays > MAX_WINDOW_DAYS) {
            throw new BadRequestException(
                    "An attribution window is between " + MIN_WINDOW_DAYS + " and " + MAX_WINDOW_DAYS + " days.");
        }
        if (startsOn != null && endsOn != null && endsOn.isBefore(startsOn)) {
            throw new BadRequestException("A campaign ends before it starts.");
        }
    }

    public static Campaign create(
            UUID id,
            TenantId tenantId,
            String name,
            String slug,
            String channel,
            AttributionModel model,
            int windowDays,
            String currency,
            LocalDate startsOn,
            LocalDate endsOn,
            Instant now) {
        return new Campaign(
                id, tenantId, name, slug, channel, CampaignStatus.ACTIVE,
                model == null ? AttributionModel.LAST : model,
                windowDays == 0 ? DEFAULT_WINDOW_DAYS : windowDays,
                currency, startsOn, endsOn, 1, now, now);
    }

    public Campaign update(
            String name,
            String channel,
            AttributionModel model,
            int windowDays,
            LocalDate startsOn,
            LocalDate endsOn,
            Instant now) {
        requireActive("edited");
        return new Campaign(
                id, tenantId, name, slug, channel, status, model, windowDays, currency, startsOn, endsOn,
                version + 1, createdAt, now);
    }

    /**
     * @param newCurrency the currency a caller asked for; it must equal the current one once anything is measured
     */
    public Campaign changeCurrency(String newCurrency, boolean measured, Instant now) {
        requireActive("changed");
        String requested = requireCurrency(newCurrency);
        if (!requested.equals(currency) && measured) {
            throw new ConflictException(
                    "A campaign's currency cannot change once spend or a conversion exists.");
        }
        return new Campaign(
                id, tenantId, name, slug, channel, status, model, windowDays, requested, startsOn, endsOn,
                version + 1, createdAt, now);
    }

    public Campaign archive(Instant now) {
        if (status.archived()) {
            return this;
        }
        return new Campaign(
                id, tenantId, name, slug, channel, CampaignStatus.ARCHIVED, model, windowDays, currency,
                startsOn, endsOn, version + 1, createdAt, now);
    }

    /** Whether the change alters the model or the window, which is what starts a recompute. */
    public boolean changesModel(AttributionModel newModel, int newWindowDays) {
        return newModel != model || newWindowDays != windowDays;
    }

    private void requireActive(String action) {
        if (status.archived()) {
            throw new ConflictException("An archived campaign can no longer be " + action + ".");
        }
    }

    private static String requireName(String name) {
        if (name == null || name.isBlank()) {
            throw new BadRequestException("A campaign needs a name.");
        }
        String trimmed = name.strip();
        if (trimmed.length() > MAX_NAME) {
            throw new BadRequestException("A campaign name is at most " + MAX_NAME + " characters.");
        }
        return trimmed;
    }

    private static String requireSlug(String slug) {
        if (slug == null || slug.isBlank()) {
            throw new BadRequestException("A campaign needs a slug.");
        }
        String normalized = slug.strip().toLowerCase(Locale.ROOT);
        if (!SLUG.matcher(normalized).matches()) {
            throw new BadRequestException(
                    "A campaign slug is 3 to 64 characters of lower-case letters, digits, '_' or '-', starting with a"
                            + " letter or digit.");
        }
        return normalized;
    }

    private static String requireCurrency(String currency) {
        if (currency == null || currency.isBlank()) {
            throw new BadRequestException("A campaign needs a currency.");
        }
        String code = currency.strip().toUpperCase(Locale.ROOT);
        try {
            // Currency.getInstance validates the ISO 4217 code; a made-up code is refused here rather than stored.
            Currency.getInstance(code);
        } catch (IllegalArgumentException e) {
            throw new BadRequestException("A campaign currency is an ISO 4217 code.");
        }
        return code;
    }

    private static String clip(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max);
    }
}
