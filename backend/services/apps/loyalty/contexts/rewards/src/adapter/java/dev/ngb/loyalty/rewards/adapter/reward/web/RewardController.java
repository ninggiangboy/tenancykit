package dev.ngb.loyalty.rewards.adapter.reward.web;

import dev.ngb.core.http.HttpHeaders;
import dev.ngb.core.query.ListQuery;
import dev.ngb.core.query.Page;
import dev.ngb.core.security.Caller;
import dev.ngb.core.security.Callers;
import dev.ngb.core.security.RequiresPermission;
import dev.ngb.core.spring.query.Listing;
import dev.ngb.core.version.ConditionalUpdate;
import dev.ngb.core.version.ExpectedVersion;
import dev.ngb.loyalty.rewards.application.reward.command.CreateRewardUseCase;
import dev.ngb.loyalty.rewards.application.reward.command.RedeemRewardUseCase;
import dev.ngb.loyalty.rewards.application.reward.command.UpdateRewardUseCase;
import dev.ngb.loyalty.rewards.application.reward.exception.RewardRefusal;
import dev.ngb.loyalty.rewards.application.reward.query.RewardListing;
import dev.ngb.loyalty.rewards.application.reward.query.RewardQueries;
import dev.ngb.loyalty.rewards.application.reward.query.RewardView;
import dev.ngb.loyalty.rewards.domain.reward.Reward;
import dev.ngb.loyalty.rewards.domain.reward.RewardId;
import dev.ngb.loyalty.rewards.adapter.voucher.web.VoucherController;
import dev.ngb.loyalty.shared.LoyaltyPermissions;
import java.time.Instant;
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
import org.springframework.web.bind.annotation.RestController;

/**
 * The rewards catalog and its redemption: the entries a Tenant maintains, and the one call that spends a member's points
 * for one of them and issues its voucher.
 *
 * <p>`active` is the wire shorthand for the stored status: {@code true} is {@code ACTIVE}, {@code false} is
 * {@code ARCHIVED}. An update names the version it read in {@code If-Match} and answers with the new {@code ETag}.
 */
@Profile("api")
@RestController
@RequestMapping
@Callers({Caller.TENANT_USER, Caller.TENANT_SYSTEM})
public class RewardController {

    /** A new catalog entry: its name and cost are required, everything else defaults or is absent. */
    public record RewardRequest(
            String name,
            String description,
            Long pointsCost,
            Long stock,
            Boolean active,
            Instant validFrom,
            Instant validTo,
            Integer voucherValidDays) {}

    /** A patch: a null field is left as it is. */
    public record RewardPatch(
            String name,
            String description,
            Long pointsCost,
            Long stock,
            Boolean active,
            Instant validFrom,
            Instant validTo,
            Integer voucherValidDays) {}

    public record RewardResponse(
            UUID rewardId,
            String name,
            String description,
            long pointsCost,
            Long stock,
            boolean active,
            Instant validFrom,
            Instant validTo,
            Integer voucherValidDays,
            long version) {

        static RewardResponse of(Reward reward) {
            return new RewardResponse(
                    reward.id().value(),
                    reward.name(),
                    reward.description(),
                    reward.pointsCost(),
                    reward.stock(),
                    !reward.archived(),
                    reward.validFrom(),
                    reward.validTo(),
                    reward.voucherValidDays(),
                    reward.version());
        }

        static RewardResponse ofView(RewardView view) {
            return new RewardResponse(
                    view.rewardId(),
                    view.name(),
                    view.description(),
                    view.pointsCost(),
                    view.stock(),
                    view.active(),
                    view.validFrom(),
                    view.validTo(),
                    view.voucherValidDays(),
                    view.version());
        }
    }

    /** The one field a reward redemption carries: which reward the member exchanges its points for. */
    public record RewardRedemptionRequest(UUID rewardId) {}

    /** The redemption, the voucher it issued and the balance the spend left. */
    public record RewardRedemptionResponse(
            UUID redemptionId,
            UUID rewardId,
            VoucherController.VoucherResponse voucher,
            long available,
            long debt,
            Instant createdAt) {

        static RewardRedemptionResponse of(RedeemRewardUseCase.Result result) {
            return new RewardRedemptionResponse(
                    result.redemptionId(),
                    result.rewardId().value(),
                    VoucherController.VoucherResponse.of(result.voucher()),
                    result.available(),
                    result.debt(),
                    result.createdAt());
        }
    }

    private final CreateRewardUseCase create;
    private final UpdateRewardUseCase update;
    private final RewardQueries queries;
    private final RedeemRewardUseCase redeem;

    public RewardController(
            CreateRewardUseCase create, UpdateRewardUseCase update, RewardQueries queries, RedeemRewardUseCase redeem) {
        this.create = create;
        this.update = update;
        this.queries = queries;
        this.redeem = redeem;
    }

    /** Filter, sort and paging follow the list-query contract; `active` narrows the catalog to what it can be redeemed from. */
    @GetMapping("/rewards")
    @Listing(value = RewardListing.class, passthrough = "active")
    @RequiresPermission(LoyaltyPermissions.REWARD_READ)
    public Page<RewardResponse> listRewards(
            ListQuery query, @RequestParam(name = "active", required = false) Boolean active) {
        return queries.list(query, active).map(RewardResponse::ofView);
    }

    @PostMapping("/rewards")
    @RequiresPermission(LoyaltyPermissions.REWARD_MANAGE)
    public ResponseEntity<RewardResponse> createReward(@RequestBody RewardRequest request) {
        Reward reward = create.execute(new CreateRewardUseCase.Command(
                request.name(),
                request.description(),
                request.pointsCost(),
                request.stock(),
                request.active(),
                null,
                request.validFrom(),
                request.validTo(),
                request.voucherValidDays()));
        return ResponseEntity.status(HttpStatus.CREATED)
                .eTag(ExpectedVersion.etag(reward.version()))
                .body(RewardResponse.of(reward));
    }

    @GetMapping("/rewards/{id}")
    @RequiresPermission(LoyaltyPermissions.REWARD_READ)
    public ResponseEntity<RewardResponse> getReward(@PathVariable String id) {
        RewardView reward = queries.get(UUID.fromString(id));
        return ResponseEntity.ok().eTag(ExpectedVersion.etag(reward.version())).body(RewardResponse.ofView(reward));
    }

    @PatchMapping("/rewards/{id}")
    @ConditionalUpdate
    @RequiresPermission(LoyaltyPermissions.REWARD_MANAGE)
    public ResponseEntity<RewardResponse> updateReward(
            @PathVariable String id,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            @RequestBody RewardPatch request) {
        Reward reward = update.execute(
                RewardId.of(id),
                new UpdateRewardUseCase.Command(
                        request.name(),
                        request.description(),
                        request.pointsCost(),
                        request.stock(),
                        request.active(),
                        null,
                        request.validFrom(),
                        request.validTo(),
                        request.voucherValidDays()),
                required(ifMatch));
        return ResponseEntity.ok().eTag(ExpectedVersion.etag(reward.version())).body(RewardResponse.of(reward));
    }

    /** 201 when this call spent the points and issued the voucher, 200 when the key had already issued it. */
    @PostMapping("/members/{externalRef}/reward-redemptions")
    @RequiresPermission(LoyaltyPermissions.REWARD_REDEEM)
    public ResponseEntity<RewardRedemptionResponse> postRewardRedemption(
            @PathVariable String externalRef,
            @RequestHeader(name = HttpHeaders.IDEMPOTENCY_KEY, required = false) String idempotencyKey,
            @RequestBody(required = false) RewardRedemptionRequest request) {
        RedeemRewardUseCase.Result result = redeem.execute(new RedeemRewardUseCase.Command(
                externalRef, idempotencyKey, request == null ? null : rewardId(request.rewardId())));
        return ResponseEntity.status(result.replayed() ? HttpStatus.OK : HttpStatus.CREATED)
                .body(RewardRedemptionResponse.of(result));
    }

    private static RewardId rewardId(UUID raw) {
        return raw == null ? null : new RewardId(raw);
    }

    /** @return the version an {@code If-Match} header accepts; 428 without it, which the contract names */
    private static ExpectedVersion required(String ifMatch) {
        if (ifMatch == null || ifMatch.isBlank()) {
            throw RewardRefusal.ifMatchRequired();
        }
        return ExpectedVersion.fromIfMatch(ifMatch);
    }
}
