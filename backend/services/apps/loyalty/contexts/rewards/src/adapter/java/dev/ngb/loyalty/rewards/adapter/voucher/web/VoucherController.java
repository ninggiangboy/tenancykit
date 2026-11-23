package dev.ngb.loyalty.rewards.adapter.voucher.web;

import dev.ngb.core.query.ListQuery;
import dev.ngb.core.query.Page;
import dev.ngb.core.security.Caller;
import dev.ngb.core.security.Callers;
import dev.ngb.core.security.RequiresPermission;
import dev.ngb.core.spring.query.Listing;
import dev.ngb.loyalty.rewards.application.voucher.command.UseVoucherUseCase;
import dev.ngb.loyalty.rewards.application.voucher.query.VoucherListing;
import dev.ngb.loyalty.rewards.application.voucher.query.VoucherQueries;
import dev.ngb.loyalty.rewards.application.voucher.query.VoucherView;
import dev.ngb.loyalty.rewards.domain.voucher.Voucher;
import dev.ngb.loyalty.shared.LoyaltyPermissions;
import java.time.Instant;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The single-use vouchers a reward redemption issues: one by the code the point of sale presents, the ones a member
 * holds, and the one call that consumes a voucher.
 *
 * <p>A use is idempotent by the caller's own {@code useRef}: the same reference answers the same used voucher, a
 * different one is a conflict and an expired voucher is refused. `expiresAt` is the only expiry there is, so a reader
 * compares it with the current instant.
 */
@Profile("api")
@RestController
@RequestMapping
@Callers({Caller.TENANT_USER, Caller.TENANT_SYSTEM})
public class VoucherController {

    /** One use as the point of sale sends it: its own reference for the sale, so a retry replays instead of consuming. */
    public record VoucherUseRequest(String useRef) {}

    public record VoucherResponse(
            UUID voucherId,
            String code,
            UUID rewardId,
            UUID memberId,
            String externalRef,
            String state,
            Instant issuedAt,
            Instant expiresAt,
            Instant usedAt,
            String useRef,
            String voidReason) {

        public static VoucherResponse of(Voucher voucher) {
            return new VoucherResponse(
                    voucher.id().value(),
                    voucher.code(),
                    voucher.rewardId(),
                    voucher.memberId(),
                    voucher.externalRef(),
                    voucher.status().name(),
                    voucher.issuedAt(),
                    voucher.expiresAt(),
                    voucher.usedAt(),
                    voucher.useRef(),
                    voucher.voidReason() == null ? null : voucher.voidReason().name());
        }

        static VoucherResponse ofView(VoucherView view) {
            return new VoucherResponse(
                    view.voucherId(),
                    view.code(),
                    view.rewardId(),
                    view.memberId(),
                    view.externalRef(),
                    view.state().name(),
                    view.issuedAt(),
                    view.expiresAt(),
                    view.usedAt(),
                    view.useRef(),
                    view.voidReason() == null ? null : view.voidReason().name());
        }
    }

    private final UseVoucherUseCase use;
    private final VoucherQueries queries;

    public VoucherController(UseVoucherUseCase use, VoucherQueries queries) {
        this.use = use;
        this.queries = queries;
    }

    /** Filter, sort and paging follow the list-query contract; `status` narrows the member's vouchers. */
    @GetMapping("/members/{externalRef}/vouchers")
    @Listing(value = VoucherListing.class, passthrough = "status")
    @RequiresPermission(LoyaltyPermissions.VOUCHER_READ)
    public Page<VoucherResponse> listMemberVouchers(
            @PathVariable String externalRef,
            @RequestParam(name = "status", required = false) String status,
            ListQuery query) {
        return queries.list(externalRef, status, query).map(VoucherResponse::ofView);
    }

    @GetMapping("/vouchers/{code}")
    @RequiresPermission(LoyaltyPermissions.VOUCHER_READ)
    public VoucherResponse getVoucher(@PathVariable String code) {
        return VoucherResponse.ofView(queries.get(code));
    }

    /** 200 whether this call consumed the voucher or the same `useRef` did it before; a different one is a conflict. */
    @PostMapping("/vouchers/{code}/use")
    @RequiresPermission(LoyaltyPermissions.VOUCHER_USE)
    public ResponseEntity<VoucherResponse> useVoucher(
            @PathVariable String code, @RequestBody(required = false) VoucherUseRequest request) {
        UseVoucherUseCase.Result result =
                use.execute(new UseVoucherUseCase.Command(code, request == null ? null : request.useRef()));
        return ResponseEntity.ok(VoucherResponse.of(result.voucher()));
    }
}
