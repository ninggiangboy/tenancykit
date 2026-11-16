package dev.ngb.attribution.links.application.campaign.command;

import dev.ngb.attribution.links.application.campaign.port.CampaignRepository;
import dev.ngb.attribution.links.domain.campaign.AttributionModel;
import dev.ngb.attribution.links.domain.campaign.Campaign;
import dev.ngb.attribution.links.domain.link.Slug;
import dev.ngb.core.error.ConflictException;
import dev.ngb.core.id.TenantId;
import dev.ngb.core.runtime.Clock;
import dev.ngb.core.runtime.IdGenerator;
import dev.ngb.core.runtime.UnitOfWork;
import dev.ngb.core.tenancy.TenantContext;
import java.time.LocalDate;
import java.util.Locale;

/**
 * Creates a campaign. A caller may omit the slug; one is then derived from the name so the common case needs no
 * second round trip, and a collision falls back to a generated slug rather than failing the request.
 */
public class CreateCampaignUseCase {

    private static final int MAX_SLUG_ATTEMPTS = 5;

    public record Command(
            String name,
            String slug,
            String channel,
            AttributionModel model,
            int windowDays,
            String currency,
            LocalDate startsOn,
            LocalDate endsOn) {}

    private final UnitOfWork unitOfWork;
    private final TenantContext tenantContext;
    private final CampaignRepository campaigns;
    private final IdGenerator ids;
    private final Clock clock;

    public CreateCampaignUseCase(
            UnitOfWork unitOfWork,
            TenantContext tenantContext,
            CampaignRepository campaigns,
            IdGenerator ids,
            Clock clock) {
        this.unitOfWork = unitOfWork;
        this.tenantContext = tenantContext;
        this.campaigns = campaigns;
        this.ids = ids;
        this.clock = clock;
    }

    public Campaign execute(Command command) {
        TenantId tenantId = tenantContext.requireTenant();
        return unitOfWork.transactional(() -> {
            String slug = resolveSlug(command.slug(), command.name());
            Campaign campaign = Campaign.create(
                    ids.newId(), tenantId, command.name(), slug, command.channel(),
                    command.model(), command.windowDays(), command.currency(),
                    command.startsOn(), command.endsOn(), clock.now());
            campaigns.insert(campaign);
            return campaign;
        });
    }

    private String resolveSlug(String requested, String name) {
        if (requested != null && !requested.isBlank()) {
            String slug = requested.strip();
            if (campaigns.slugTaken(slug)) {
                throw new ConflictException("A campaign with that slug already exists.");
            }
            return slug;
        }
        String candidate = deriveSlug(name);
        for (int attempt = 0; attempt < MAX_SLUG_ATTEMPTS; attempt++) {
            if (!campaigns.slugTaken(candidate)) {
                return candidate;
            }
            candidate = Slug.generate().value();
        }
        throw new ConflictException("A unique campaign slug could not be generated.");
    }

    private static String deriveSlug(String name) {
        String collapsed = name == null ? "" : name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-");
        String candidate = stripDashes(collapsed);
        if (candidate.length() > Slug.MAX_LENGTH) {
            candidate = stripDashes(candidate.substring(0, Slug.MAX_LENGTH));
        }
        if (candidate.length() < Slug.MIN_LENGTH) {
            return Slug.generate().value();
        }
        return candidate;
    }

    private static String stripDashes(String value) {
        int start = 0;
        int end = value.length();
        while (start < end && value.charAt(start) == '-') {
            start++;
        }
        while (end > start && value.charAt(end - 1) == '-') {
            end--;
        }
        return value.substring(start, end);
    }
}
