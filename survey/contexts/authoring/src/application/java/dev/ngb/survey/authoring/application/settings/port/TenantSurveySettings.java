package dev.ngb.survey.authoring.application.settings.port;

import java.util.Optional;
import java.util.UUID;

/**
 * A tenant's survey defaults, which a new version inherits and a public endpoint reads.
 *
 * <p>Every column is nullable because a missing row means the configured default: a tenant overrides only what it means
 * to change, and a new default reaches every tenant that never set one.
 *
 * @param abandonAfter the idle window after which an unfinished visit is abandoned, or null for the deployment default
 * @param publicRatePerMinute one caller's public requests per minute, or null for the deployment default
 */
public record TenantSurveySettings(
        UUID tenantId,
        String defaultLocale,
        java.time.Duration abandonAfter,
        Long uploadMaxBytes,
        Integer publicRatePerMinute,
        String timezone,
        long version) {

    /** The settings of a tenant that never changed one, which is what a read answers before the first write. */
    public static TenantSurveySettings defaults(UUID tenantId) {
        return new TenantSurveySettings(tenantId, null, null, null, null, null, 0);
    }

    /**
     * The same row with the fields a caller changed; a null field is left as it is.
     *
     * @param newAbandonAfter the idle window, or null to keep the current one
     */
    public TenantSurveySettings changed(
            String newDefaultLocale,
            java.time.Duration newAbandonAfter,
            Long newUploadMaxBytes,
            Integer newPublicRatePerMinute,
            String newTimezone) {
        return new TenantSurveySettings(
                tenantId,
                newDefaultLocale == null ? defaultLocale : newDefaultLocale,
                newAbandonAfter == null ? abandonAfter : newAbandonAfter,
                newUploadMaxBytes == null ? uploadMaxBytes : newUploadMaxBytes,
                newPublicRatePerMinute == null ? publicRatePerMinute : newPublicRatePerMinute,
                newTimezone == null ? timezone : newTimezone,
                version + 1);
    }
}
