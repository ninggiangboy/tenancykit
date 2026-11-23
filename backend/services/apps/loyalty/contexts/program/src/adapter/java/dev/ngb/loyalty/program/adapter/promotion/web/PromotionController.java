package dev.ngb.loyalty.program.adapter.promotion.web;

import dev.ngb.core.http.HttpHeaders;
import dev.ngb.core.query.ListQuery;
import dev.ngb.core.query.Page;
import dev.ngb.core.security.Caller;
import dev.ngb.core.security.Callers;
import dev.ngb.core.security.RequiresPermission;
import dev.ngb.core.spring.query.Listing;
import dev.ngb.core.version.ConditionalUpdate;
import dev.ngb.core.version.ExpectedVersion;
import dev.ngb.loyalty.program.adapter.program.web.Preconditions;
import dev.ngb.loyalty.program.api.promotion.PromotionStatus;
import dev.ngb.loyalty.program.api.promotion.PromotionView;
import dev.ngb.loyalty.program.application.promotion.command.CreatePromotionUseCase;
import dev.ngb.loyalty.program.application.promotion.command.UpdatePromotionUseCase;
import dev.ngb.loyalty.program.application.promotion.query.PromotionListing;
import dev.ngb.loyalty.program.application.promotion.query.PromotionQueries;
import dev.ngb.loyalty.program.application.promotion.service.PromotionRules;
import dev.ngb.loyalty.program.domain.promotion.Promotion;
import dev.ngb.loyalty.program.domain.promotion.PromotionConditions;
import dev.ngb.loyalty.program.domain.promotion.PromotionId;
import dev.ngb.loyalty.shared.LoyaltyPermissions;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * The promotion catalog. A promotion multiplies what a matching line earns; its conditions are the closed set of lists
 * and one local time range, and only an active promotion inside its window is considered.
 *
 * <p>`active` is the wire shorthand for the stored status: {@code true} is {@code ACTIVE}, {@code false} is
 * {@code PAUSED}. A caller that needs the third state sends {@code status} with {@code ARCHIVED}.
 */
@Profile("api")
@RestController
@RequestMapping
@Callers({Caller.TENANT_USER, Caller.TENANT_SYSTEM})
public class PromotionController {

    /** A new promotion: its name and multiplier are required, everything else defaults or is absent. */
    public record PromotionRequest(
            String name,
            Integer priority,
            Boolean active,
            String status,
            BigDecimal multiplier,
            Instant startsAt,
            Instant endsAt,
            List<Integer> daysOfWeek,
            List<String> storeCodes,
            List<String> categories,
            List<String> skus,
            List<String> tierCodes,
            String timeFrom,
            String timeTo) {}

    /** A patch: a null field is left as it is, and an empty list is an explicit "no restriction" for that kind. */
    public record PromotionPatch(
            String name,
            Integer priority,
            Boolean active,
            String status,
            BigDecimal multiplier,
            Instant startsAt,
            Instant endsAt,
            List<Integer> daysOfWeek,
            List<String> storeCodes,
            List<String> categories,
            List<String> skus,
            List<String> tierCodes,
            String timeFrom,
            String timeTo) {}

    public record PromotionResponse(
            String promotionId,
            String name,
            int priority,
            boolean active,
            String status,
            BigDecimal multiplier,
            Instant startsAt,
            Instant endsAt,
            List<Integer> daysOfWeek,
            List<String> storeCodes,
            List<String> categories,
            List<String> skus,
            List<String> tierCodes,
            String timeFrom,
            String timeTo,
            long version) {

        static PromotionResponse of(Promotion promotion) {
            PromotionConditions conditions = promotion.conditions();
            return new PromotionResponse(
                    promotion.id().value().toString(),
                    promotion.name(),
                    promotion.priority(),
                    promotion.status() == PromotionStatus.ACTIVE,
                    promotion.status().name(),
                    promotion.multiplier(),
                    promotion.startsAt(),
                    promotion.endsAt(),
                    conditions.daysOfWeek(),
                    conditions.storeCodes(),
                    conditions.categories(),
                    conditions.skus(),
                    conditions.tierCodes(),
                    time(conditions.timeFrom()),
                    time(conditions.timeTo()),
                    promotion.version());
        }

        static PromotionResponse ofView(PromotionView view) {
            return new PromotionResponse(
                    view.promotionId().toString(),
                    view.name(),
                    view.priority(),
                    view.active(),
                    view.status().name(),
                    view.multiplier(),
                    view.startsAt(),
                    view.endsAt(),
                    view.daysOfWeek(),
                    view.storeCodes(),
                    view.categories(),
                    view.skus(),
                    view.tierCodes(),
                    time(view.timeFrom()),
                    time(view.timeTo()),
                    view.version());
        }

        private static String time(LocalTime value) {
            return value == null ? null : value.toString();
        }
    }

    private final CreatePromotionUseCase create;
    private final UpdatePromotionUseCase update;
    private final PromotionQueries queries;

    public PromotionController(CreatePromotionUseCase create, UpdatePromotionUseCase update, PromotionQueries queries) {
        this.create = create;
        this.update = update;
        this.queries = queries;
    }

    /** Filter, sort and paging follow the list-query contract; `active` narrows the catalog to open or closed rules. */
    @GetMapping("/promotions")
    @Listing(value = PromotionListing.class, passthrough = "active")
    @RequiresPermission(LoyaltyPermissions.PROGRAM_READ)
    public Page<PromotionResponse> listPromotions(
            ListQuery query, @RequestParam(name = "active", required = false) Boolean active) {
        return queries.list(query, active).map(PromotionResponse::ofView);
    }

    @Callers(Caller.TENANT_USER)
    @PostMapping("/promotions")
    @ResponseStatus(HttpStatus.CREATED)
    @RequiresPermission(LoyaltyPermissions.PROGRAM_MANAGE)
    public ResponseEntity<PromotionResponse> createPromotion(
            @RequestHeader(value = HttpHeaders.REQUEST_ID, required = false) String requestId,
            @RequestBody PromotionRequest request) {
        Promotion promotion = create.execute(new CreatePromotionUseCase.Command(
                request.name(),
                request.priority(),
                request.status(),
                request.active(),
                request.multiplier(),
                request.startsAt(),
                request.endsAt(),
                conditions(
                        request.categories(),
                        request.skus(),
                        request.storeCodes(),
                        request.daysOfWeek(),
                        request.tierCodes(),
                        request.timeFrom(),
                        request.timeTo())));
        return ResponseEntity.status(HttpStatus.CREATED)
                .eTag(ExpectedVersion.etag(promotion.version()))
                .body(PromotionResponse.of(promotion));
    }

    @GetMapping("/promotions/{id}")
    @RequiresPermission(LoyaltyPermissions.PROGRAM_READ)
    public ResponseEntity<PromotionResponse> getPromotion(@PathVariable String id) {
        PromotionView promotion = queries.get(UUID.fromString(id));
        return ResponseEntity.ok()
                .eTag(ExpectedVersion.etag(promotion.version()))
                .body(PromotionResponse.ofView(promotion));
    }

    @Callers(Caller.TENANT_USER)
    @PatchMapping("/promotions/{id}")
    @RequiresPermission(LoyaltyPermissions.PROGRAM_MANAGE)
    @ConditionalUpdate
    public ResponseEntity<PromotionResponse> updatePromotion(
            @PathVariable String id,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            @RequestHeader(value = HttpHeaders.REQUEST_ID, required = false) String requestId,
            @RequestBody PromotionPatch request) {
        Promotion promotion = update.execute(
                PromotionId.of(id),
                new UpdatePromotionUseCase.Command(
                        request.name(),
                        request.priority(),
                        request.status(),
                        request.active(),
                        request.multiplier(),
                        request.startsAt(),
                        request.endsAt(),
                        conditions(
                                request.categories(),
                                request.skus(),
                                request.storeCodes(),
                                request.daysOfWeek(),
                                request.tierCodes(),
                                request.timeFrom(),
                                request.timeTo())),
                Preconditions.required(ifMatch));
        return ResponseEntity.ok()
                .eTag(ExpectedVersion.etag(promotion.version()))
                .body(PromotionResponse.of(promotion));
    }

    /** @return the condition set a request asked for, or null when it named none at all */
    private static PromotionConditions conditions(
            List<String> categories,
            List<String> skus,
            List<String> storeCodes,
            List<Integer> daysOfWeek,
            List<String> tierCodes,
            String timeFrom,
            String timeTo) {
        if (categories == null
                && skus == null
                && storeCodes == null
                && daysOfWeek == null
                && tierCodes == null
                && timeFrom == null
                && timeTo == null) {
            return null;
        }
        return new PromotionConditions(
                categories,
                skus,
                storeCodes,
                daysOfWeek,
                tierCodes,
                PromotionRules.localTime(timeFrom),
                PromotionRules.localTime(timeTo));
    }
}
