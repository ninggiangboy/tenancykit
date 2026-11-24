package dev.ngb.billing.pricing.adapter.plan.web;

import dev.ngb.core.http.HttpHeaders;
import dev.ngb.core.security.Caller;
import dev.ngb.core.security.Callers;
import dev.ngb.core.security.CurrentActor;
import dev.ngb.billing.sharedkernel.security.BillingPermissions;
import dev.ngb.core.security.RequiresPermission;
import dev.ngb.core.audit.Audit;
import dev.ngb.billing.pricing.adapter.plan.json.RuleDto;
import dev.ngb.billing.pricing.api.plan.BillingCycle;
import dev.ngb.billing.pricing.application.plan.command.ManagePlanUseCase;
import dev.ngb.billing.pricing.application.plan.query.PlanListing;
import dev.ngb.billing.pricing.application.plan.query.PlanQueries;
import dev.ngb.billing.pricing.application.plan.query.PlanVersionView;
import dev.ngb.billing.pricing.application.plan.query.PricingPlanView;
import dev.ngb.core.query.ListQuery;
import dev.ngb.core.query.Page;
import dev.ngb.core.spring.query.Listing;
import dev.ngb.billing.pricing.domain.plan.PricingPlanId;
import dev.ngb.billing.pricing.domain.plan.PlanStatus;
import dev.ngb.billing.pricing.domain.plan.PlanVersion;
import dev.ngb.billing.pricing.domain.plan.PricingPlan;
import dev.ngb.billing.pricing.domain.plan.RoundingPolicy;
import dev.ngb.billing.pricing.domain.rule.PricingModel;
import dev.ngb.billing.sharedkernel.money.Currency;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.json.JsonMapper;

/** Pricing plans and their versions. Prices are decimals in minor units of the plan's currency. */
@Tag(
        name = "Pricing plans",
        description =
                "Pricing plans and their versions.")
@Profile("api")
@RestController
@RequestMapping
@Callers(Caller.TENANT_USER)
public class PlanController {

    public record VersionRequest(
            RuleDto rule, String billingCycle, Long minCharge, Long maxCharge, String rounding, Instant validFrom, Instant validUntil) {}

    public record CreatePlanRequest(String code, String name, String currency, String model, VersionRequest version) {}

    public record VersionResponse(
            int version, RuleDto rule, String billingCycle, Long minCharge, Long maxCharge, String rounding, Instant validFrom, Instant validUntil,
            Instant createdAt, String createdBy) {
        static VersionResponse of(PlanVersion v) {
            return new VersionResponse(v.number(), RuleDto.of(v.rule()), v.billingCycle() == null ? null : v.billingCycle().name(), v.minCharge(), v.maxCharge(),
                    v.rounding().name(), v.validFrom(), v.validUntil(), v.createdAt(), v.createdBy());
        }

        static VersionResponse ofView(PlanVersionView v, JsonMapper json) {
            return new VersionResponse(v.version(), json.readValue(v.rule(), RuleDto.class),
                    v.billingCycle() == null ? null : v.billingCycle().name(), v.minCharge(), v.maxCharge(), v.rounding().name(),
                    v.validFrom(), v.validUntil(), v.createdAt(), v.createdBy());
        }
    }

    public record PlanResponse(
            String id, String code, String name, String model, String currency, String status, Instant createdAt, Instant retiredAt, List<VersionResponse> versions) {
        static PlanResponse of(PricingPlan p) {
            return new PlanResponse(p.id().toString(), p.code(), p.name(), p.model().name(), p.currency().code(), p.status().name(), p.createdAt(), p.retiredAt(),
                    p.versions().stream().map(VersionResponse::of).toList());
        }

        static PlanResponse ofView(PricingPlanView p, JsonMapper json) {
            return new PlanResponse(p.id().toString(), p.code(), p.name(), p.model().name(), p.currency(), p.status().name(), p.createdAt(), p.retiredAt(),
                    p.versions().stream().map(v -> VersionResponse.ofView(v, json)).toList());
        }
    }

    private final ManagePlanUseCase plans;
    private final PlanQueries planQueries;
    private final CurrentActor currentActor;
    private final JsonMapper json;

    public PlanController(ManagePlanUseCase plans, PlanQueries planQueries, CurrentActor currentActor, JsonMapper json) {
        this.plans = plans;
        this.planQueries = planQueries;
        this.currentActor = currentActor;
        this.json = json;
    }

    @Operation(
            summary = "Create a pricing plan",
            description =
                    "Creates a plan with a `code`, a name, a currency, a pricing `model` and its first version. Prices "
                    + "are decimals in minor units of the plan's currency. Creating a plan fixes the unit of each metric it "
                    + "prices; a later plan that measures the same metric in another unit is refused. Answers `201`.")
    @PostMapping("/pricing-plans")
    @ResponseStatus(HttpStatus.CREATED)
    @RequiresPermission(BillingPermissions.PRICING_MANAGE)
    public PlanResponse createPlan(@RequestHeader(value = HttpHeaders.REQUEST_ID, required = false) String requestId, @RequestBody CreatePlanRequest request) {
        if (request.version() == null || request.version().rule() == null || request.model() == null) {
            throw new IllegalArgumentException("model and version.rule are required");
        }
        PricingModel model = PricingModel.valueOf(request.model());
        return PlanResponse.of(plans.create(Audit.of(currentActor.get().auditName(), requestId), request.code(), request.name(), Currency.of(required(request.currency(), "currency")),
                terms(request.version(), model)));
    }

    /** Tenant systems list the plans they may offer; filter, sort and paging follow the list-query contract. */
    @Callers({Caller.TENANT_USER, Caller.TENANT_SYSTEM})
    @Operation(
            summary = "List pricing plans",
            description =
                    "Pages through the plans a tenant system may offer, with the list-query contract's filtering, sorting "
                    + "and paging.")
    @GetMapping("/pricing-plans")
    @Listing(PlanListing.class)
    @RequiresPermission(BillingPermissions.PRICING_READ)
    public Page<PlanResponse> listPlans(ListQuery query) {
        return planQueries.list(query).map(view -> PlanResponse.ofView(view, json));
    }

    @Callers({Caller.TENANT_USER, Caller.TENANT_SYSTEM})
    @Operation(
            summary = "Get a pricing plan",
            description =
                    "Returns one plan with its current version. An unknown id answers `404`.")
    @GetMapping("/pricing-plans/{id}")
    @RequiresPermission(BillingPermissions.PRICING_READ)
    public PlanResponse getPlan(@PathVariable String id) {
        return PlanResponse.ofView(planQueries.get(UUID.fromString(id)), json);
    }

    /** Adds the next version. Earlier versions, and every charge that used them, are unchanged. */
    @Operation(
            summary = "Publish a plan version",
            description =
                    "Adds the next version of the plan. Earlier versions, and every charge that used them, are unchanged. "
                    + "Answers `201`.")
    @PostMapping("/pricing-plans/{id}/versions")
    @ResponseStatus(HttpStatus.CREATED)
    @RequiresPermission(BillingPermissions.PRICING_MANAGE)
    public VersionResponse publishVersion(
            @PathVariable String id, @RequestHeader(value = HttpHeaders.REQUEST_ID, required = false) String requestId, @RequestBody VersionRequest request) {
        if (request.rule() == null) {
            throw new IllegalArgumentException("rule is required");
        }
        PricingPlanId planId = PricingPlanId.of(id);
        return VersionResponse.of(plans.publishVersion(Audit.of(currentActor.get().auditName(), requestId), planId, terms(request, plans.get(planId).model())));
    }

    @Operation(
            summary = "Retire a pricing plan",
            description =
                    "Retires the plan so it can no longer be offered; existing versions and the charges that used them "
                    + "stay.")
    @PostMapping("/pricing-plans/{id}/retire")
    @RequiresPermission(BillingPermissions.PRICING_MANAGE)
    public PlanResponse retire(@PathVariable String id, @RequestHeader(value = HttpHeaders.REQUEST_ID, required = false) String requestId) {
        return PlanResponse.of(plans.retire(Audit.of(currentActor.get().auditName(), requestId), PricingPlanId.of(id)));
    }

    private static ManagePlanUseCase.Terms terms(VersionRequest v, PricingModel model) {
        return new ManagePlanUseCase.Terms(v.rule().toRule(model), v.billingCycle() == null ? null : BillingCycle.valueOf(v.billingCycle()), v.minCharge(),
                v.maxCharge(), v.rounding() == null ? null : RoundingPolicy.valueOf(v.rounding()), v.validFrom(), v.validUntil());
    }

    private static String required(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
        return value;
    }
}
