package dev.ngb.billing.coupon.adapter.coupon.web;

import dev.ngb.billing.coupon.application.coupon.command.CreateCouponUseCase;
import dev.ngb.billing.coupon.application.coupon.command.DeactivateCouponUseCase;
import dev.ngb.billing.coupon.application.coupon.command.ManagePromotionCodeUseCase;
import dev.ngb.billing.coupon.application.coupon.query.CouponListing;
import dev.ngb.billing.coupon.application.coupon.query.CouponQueries;
import dev.ngb.billing.coupon.application.coupon.query.CouponView;
import dev.ngb.billing.coupon.application.coupon.query.PromotionCodeListing;
import dev.ngb.billing.coupon.application.coupon.query.PromotionCodeView;
import dev.ngb.core.query.ListQuery;
import dev.ngb.core.query.Page;
import dev.ngb.core.security.Caller;
import dev.ngb.core.security.Callers;
import dev.ngb.core.spring.query.Listing;
import dev.ngb.billing.coupon.domain.coupon.Benefit;
import dev.ngb.billing.coupon.domain.coupon.ChargeKind;
import dev.ngb.billing.coupon.domain.coupon.Coupon;
import dev.ngb.billing.coupon.domain.coupon.CouponId;
import dev.ngb.billing.coupon.domain.coupon.Duration;
import dev.ngb.billing.coupon.domain.coupon.Limits;
import dev.ngb.billing.coupon.domain.coupon.PromotionCode;
import dev.ngb.billing.coupon.domain.coupon.PromotionCodeId;
import dev.ngb.billing.coupon.domain.coupon.Scope;
import dev.ngb.billing.coupon.domain.coupon.TimeWindow;
import dev.ngb.billing.sharedkernel.money.Currency;
import dev.ngb.billing.sharedkernel.money.Money;
import dev.ngb.core.audit.Audit;
import dev.ngb.core.http.HttpHeaders;
import dev.ngb.core.security.CurrentActor;
import dev.ngb.core.security.RequiresPermission;
import dev.ngb.billing.sharedkernel.security.BillingPermissions;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Instant;
import java.util.LinkedHashSet;
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

/**
 * Coupons and promotion codes for a Tenant. An Administrator and a Tenant System both create them, because a Tenant
 * runs its own promotions; the creating actor is stored on every coupon. Terms are never edited, so the only writes
 * after creation are a deactivation and a new code.
 */
@Tag(
        name = "Coupons",
        description =
                "Coupons and promotion codes. Terms are never edited, so the only writes after creation are a "
                + "deactivation and a new code.")
@Profile("api")
@RestController
@RequestMapping
@Callers({Caller.TENANT_USER, Caller.TENANT_SYSTEM})
public class CouponController {

    /** The benefit as a request spells it: one of the three shapes, with the fields that shape uses. */
    public record BenefitRequest(String type, Integer basisPoints, Long amount) {}

    public record WindowRequest(Integer count, String unit) {}

    public record LimitsRequest(
            Instant redeemBy, Long maxRedemptions, Integer maxPerCustomer, Long minCharge, Long maxDiscountPerApplication, Long budget) {}

    public record ScopeRequest(List<String> kinds, List<UUID> planIds, List<String> metrics) {}

    public record CreateCouponRequest(
            String name,
            String currency,
            BenefitRequest benefit,
            WindowRequest window,
            Integer maxApplications,
            Long maxTotalDiscount,
            LimitsRequest limits,
            ScopeRequest scope,
            boolean stackable,
            String code,
            Long codeMaxRedemptions) {}

    public record CreateCouponResponse(CouponResponse coupon, PromotionCodeView code) {}

    public record CouponResponse(
            String id,
            String name,
            String benefitType,
            Integer percentBasisPoints,
            Long amountOff,
            Long creditAmount,
            String currency,
            Instant redeemBy,
            Long maxRedemptions,
            Integer maxPerCustomer,
            long redemptionsCount,
            Long budget,
            long budgetUsed,
            boolean stackable,
            String status,
            String createdBy,
            Instant createdAt,
            Instant deactivatedAt,
            List<UUID> planIds) {
        static CouponResponse of(CouponView v) {
            return new CouponResponse(v.id().toString(), v.name(), v.benefitType(), v.percentBasisPoints(), v.amountOff(), v.creditAmount(),
                    v.currency(), v.redeemBy(), v.maxRedemptions(), v.maxPerCustomer(), v.redemptionsCount(), v.budget(), v.budgetUsed(),
                    v.stackable(), v.status(), v.createdBy(), v.createdAt(), v.deactivatedAt(), v.planIds());
        }

        static CouponResponse of(Coupon c) {
            Benefit benefit = c.benefit();
            return new CouponResponse(c.id().toString(), c.name(), benefit.type(),
                    benefit instanceof Benefit.PercentOff percent ? percent.basisPoints() : null,
                    benefit instanceof Benefit.AmountOff amountOff ? amountOff.amount().minorUnits() : null,
                    benefit instanceof Benefit.WalletCredit credit ? credit.amount().minorUnits() : null,
                    c.currency().code(), c.limits().redeemBy(), c.limits().maxRedemptions(), c.limits().maxPerCustomer(), 0,
                    c.limits().budget() == null ? null : c.limits().budget().minorUnits(), 0, c.stackable(), c.status().name(), c.createdBy(),
                    c.createdAt(), c.deactivatedAt(), List.copyOf(c.scope().planIds()));
        }
    }

    public record AddCodeRequest(String code, Long maxRedemptions, Instant redeemBy) {}

    public record PromotionCodeResponse(
            String id, String code, String couponId, Long maxRedemptions, long redeemedCount, Instant redeemBy, boolean active, Instant createdAt) {
        static PromotionCodeResponse of(PromotionCodeView v) {
            return new PromotionCodeResponse(v.id().toString(), v.code(), v.couponId().toString(), v.maxRedemptions(), v.redeemedCount(),
                    v.redeemBy(), v.active(), v.createdAt());
        }

        static PromotionCodeResponse of(PromotionCode c) {
            return new PromotionCodeResponse(c.id().toString(), c.code(), c.couponId().toString(), c.maxRedemptions(), c.redeemedCount(),
                    c.redeemBy(), c.isActive(), c.createdAt());
        }
    }

    public record DeactivateCouponRequest(boolean revokeRedemptions) {}

    private final CreateCouponUseCase createCoupon;
    private final DeactivateCouponUseCase deactivateCoupon;
    private final ManagePromotionCodeUseCase manageCodes;
    private final CouponQueries queries;
    private final CurrentActor currentActor;

    public CouponController(
            CreateCouponUseCase createCoupon, DeactivateCouponUseCase deactivateCoupon, ManagePromotionCodeUseCase manageCodes,
            CouponQueries queries, CurrentActor currentActor) {
        this.createCoupon = createCoupon;
        this.deactivateCoupon = deactivateCoupon;
        this.manageCodes = manageCodes;
        this.queries = queries;
        this.currentActor = currentActor;
    }

    @Operation(
            summary = "Create a coupon",
            description =
                    "Creates a coupon with immutable terms: a benefit (a percentage in basis points or a fixed amount), "
                    + "an optional window, limits and scope, and optionally its first promotion code. A coupon without a "
                    + "total bound, a benefit out of range or a currency mismatch is refused before anything is stored. An "
                    + "Administrator and a Tenant System both create coupons, and the creating actor is stored on the "
                    + "coupon. Answers `201`.")
    @PostMapping("/coupons")
    @ResponseStatus(HttpStatus.CREATED)
    @RequiresPermission(BillingPermissions.COUPON_MANAGE)
    public CreateCouponResponse createCoupon(
            @RequestHeader(value = HttpHeaders.REQUEST_ID, required = false) String requestId, @RequestBody CreateCouponRequest request) {
        Currency currency = Currency.of(request.currency());
        CreateCouponUseCase.Result result = createCoupon.execute(new CreateCouponUseCase.Command(
                request.name(),
                benefit(request.benefit(), currency),
                new Duration(window(request.window()), request.maxApplications(), money(request.maxTotalDiscount(), currency)),
                scope(request.scope()),
                limits(request.limits(), currency),
                currency,
                request.stackable(),
                request.code(),
                request.codeMaxRedemptions(),
                Audit.of(currentActor.get().auditName(), requestId)));
        CouponView coupon = queries.coupon(result.couponId().value());
        PromotionCodeView code = result.codeId() == null ? null : queries.code(result.codeId().value());
        return new CreateCouponResponse(CouponResponse.of(coupon), code);
    }

    /** Filter, sort and paging follow the list-query contract; the documented parameters come from {@link CouponListing}. */
    @Operation(
            summary = "List coupons",
            description =
                    "Pages through the tenant's coupons, with the list-query contract's filtering, sorting and paging.")
    @GetMapping("/coupons")
    @Listing(CouponListing.class)
    @RequiresPermission(BillingPermissions.COUPON_READ)
    public Page<CouponResponse> coupons(ListQuery query) {
        return queries.coupons(query).map(CouponResponse::of);
    }

    @Operation(
            summary = "Get a coupon",
            description =
                    "Returns one coupon with its terms and how much has been redeemed. An unknown id answers `404`.")
    @GetMapping("/coupons/{id}")
    @RequiresPermission(BillingPermissions.COUPON_READ)
    public CouponResponse coupon(@PathVariable String id) {
        return CouponResponse.of(queries.coupon(UUID.fromString(id)));
    }

    /** Stops new redemptions; running ones keep working unless {@code revokeRedemptions} is set. */
    @Operation(
            summary = "Deactivate a coupon",
            description =
                    "Final: stops new redemptions of the coupon and deactivates its codes. A running redemption keeps "
                    + "working unless `revokeRedemptions` is set, which pulls a bad coupon without invalidating a charge "
                    + "that already used it.")
    @PostMapping("/coupons/{id}/deactivate")
    @RequiresPermission(BillingPermissions.COUPON_MANAGE)
    public CouponResponse deactivateCoupon(
            @PathVariable String id, @RequestHeader(value = HttpHeaders.REQUEST_ID, required = false) String requestId,
            @RequestBody(required = false) DeactivateCouponRequest request) {
        boolean revoke = request != null && request.revokeRedemptions();
        Coupon coupon = deactivateCoupon.execute(new DeactivateCouponUseCase.Command(CouponId.of(id), revoke,
                Audit.of(currentActor.get().auditName(), requestId)));
        return CouponResponse.of(queries.coupon(coupon.id().value()));
    }

    @Operation(
            summary = "Add a promotion code",
            description =
                    "Adds a promotion code to a coupon, with an optional redemption limit and `redeemBy` date. A code "
                    + "never outlives its coupon's terms. Answers `201`.")
    @PostMapping("/coupons/{id}/codes")
    @ResponseStatus(HttpStatus.CREATED)
    @RequiresPermission(BillingPermissions.COUPON_MANAGE)
    public PromotionCodeResponse addCode(
            @PathVariable String id, @RequestHeader(value = HttpHeaders.REQUEST_ID, required = false) String requestId,
            @RequestBody AddCodeRequest request) {
        PromotionCode code = manageCodes.add(new ManagePromotionCodeUseCase.AddCommand(CouponId.of(id), request.code(),
                request.maxRedemptions(), request.redeemBy(), Audit.of(currentActor.get().auditName(), requestId)));
        return PromotionCodeResponse.of(code);
    }

    /** Filter, sort and paging follow the list-query contract; the coupon is fixed by the path. */
    @Operation(
            summary = "List a coupon's promotion codes",
            description =
                    "Pages through the promotion codes of the coupon named by the path, with the list-query contract's "
                    + "filtering, sorting and paging.")
    @GetMapping("/coupons/{id}/codes")
    @Listing(PromotionCodeListing.class)
    @RequiresPermission(BillingPermissions.COUPON_READ)
    public Page<PromotionCodeResponse> codes(@PathVariable String id, ListQuery query) {
        return queries.codesOf(UUID.fromString(id), query).map(PromotionCodeResponse::of);
    }

    @Operation(
            summary = "Deactivate a promotion code",
            description =
                    "Stops a promotion code being redeemed; the coupon and its other codes are unaffected.")
    @PostMapping("/promotion-codes/{id}/deactivate")
    @RequiresPermission(BillingPermissions.COUPON_MANAGE)
    public PromotionCodeResponse deactivateCode(
            @PathVariable String id, @RequestHeader(value = HttpHeaders.REQUEST_ID, required = false) String requestId) {
        PromotionCode code = manageCodes.deactivate(new ManagePromotionCodeUseCase.DeactivateCommand(PromotionCodeId.of(id),
                Audit.of(currentActor.get().auditName(), requestId)));
        return PromotionCodeResponse.of(code);
    }

    private static Benefit benefit(BenefitRequest request, Currency currency) {
        if (request == null || request.type() == null) {
            throw new IllegalArgumentException("a benefit is required");
        }
        return switch (request.type()) {
            case "PERCENT_OFF" -> new Benefit.PercentOff(require(request.basisPoints(), "basisPoints"));
            case "AMOUNT_OFF" -> new Benefit.AmountOff(Money.of(require(request.amount(), "amount"), currency));
            case "WALLET_CREDIT" -> new Benefit.WalletCredit(Money.of(require(request.amount(), "amount"), currency));
            default -> throw new IllegalArgumentException("unknown benefit type " + request.type());
        };
    }

    private static TimeWindow window(WindowRequest request) {
        if (request == null) {
            return null;
        }
        return new TimeWindow(require(request.count(), "window.count"), TimeWindow.Unit.valueOf(request.unit()));
    }

    private static Limits limits(LimitsRequest request, Currency currency) {
        LimitsRequest source = request == null
                ? new LimitsRequest(null, null, null, null, null, null)
                : request;
        return new Limits(source.redeemBy(), source.maxRedemptions(), source.maxPerCustomer(), money(source.minCharge(), currency),
                money(source.maxDiscountPerApplication(), currency), money(source.budget(), currency));
    }

    private static Scope scope(ScopeRequest request) {
        if (request == null) {
            return Scope.everything();
        }
        return new Scope(
                request.kinds() == null ? java.util.Set.of() : request.kinds().stream().map(ChargeKind::valueOf)
                        .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new)),
                request.planIds() == null ? java.util.Set.of() : new LinkedHashSet<>(request.planIds()),
                request.metrics() == null ? java.util.Set.of() : new LinkedHashSet<>(request.metrics()));
    }

    private static Money money(Long minorUnits, Currency currency) {
        return minorUnits == null ? null : Money.of(minorUnits, currency);
    }

    private static <T> T require(T value, String name) {
        if (value == null) {
            throw new IllegalArgumentException(name + " is required");
        }
        return value;
    }
}
