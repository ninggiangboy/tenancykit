package dev.ngb.loyalty.program.adapter.tier.web;

import dev.ngb.core.http.HttpHeaders;
import dev.ngb.core.security.RequiresPermission;
import dev.ngb.core.version.ConditionalUpdate;
import dev.ngb.core.version.ExpectedVersion;
import dev.ngb.loyalty.program.adapter.program.web.Preconditions;
import dev.ngb.loyalty.program.api.tier.TierView;
import dev.ngb.loyalty.program.application.tier.command.ReplaceTiersUseCase;
import dev.ngb.loyalty.program.application.tier.service.TierRules;
import dev.ngb.loyalty.program.application.tier.query.TierListView;
import dev.ngb.loyalty.program.application.tier.query.TierQueries;
import dev.ngb.loyalty.shared.LoyaltyPermissions;
import java.math.BigDecimal;
import java.util.List;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The ordered tier list. It belongs to the program, so it cannot exist before one does and its version is the program's:
 * a tier change bumps it and marks the list dirty, and the evaluation run then recomputes every member's tier.
 *
 * <p>A replace works by {@code code}: an existing code keeps its identity, a new code is added, and a code leaves only
 * while no member holds it.
 */
@Profile("api")
@RestController
@RequestMapping("/api/v1")
public class TierController {

    public record TierEntryRequest(String code, String name, Long minSpend, BigDecimal earnMultiplier) {}

    public record TierListRequest(List<TierEntryRequest> tiers) {}

    public record TierResponse(String code, String name, long minSpend, BigDecimal earnMultiplier) {

        static TierResponse of(TierView view) {
            return new TierResponse(view.code(), view.name(), view.minSpend(), view.earnMultiplier());
        }
    }

    public record TierListResponse(List<TierResponse> tiers, long version) {

        static TierListResponse of(TierListView view) {
            return new TierListResponse(view.tiers().stream().map(TierResponse::of).toList(), view.version());
        }
    }

    private final ReplaceTiersUseCase replace;
    private final TierQueries queries;

    public TierController(ReplaceTiersUseCase replace, TierQueries queries) {
        this.replace = replace;
        this.queries = queries;
    }

    @GetMapping("/tiers")
    @RequiresPermission(LoyaltyPermissions.PROGRAM_READ)
    public ResponseEntity<TierListResponse> getTiers() {
        TierListView tiers = queries.list();
        return ResponseEntity.ok()
                .eTag(ExpectedVersion.etag(tiers.version()))
                .body(TierListResponse.of(tiers));
    }

    @PutMapping("/tiers")
    @RequiresPermission(LoyaltyPermissions.PROGRAM_MANAGE)
    @ConditionalUpdate
    public ResponseEntity<TierListResponse> putTiers(
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            @RequestHeader(value = HttpHeaders.REQUEST_ID, required = false) String requestId,
            @RequestBody TierListRequest request) {
        List<TierRules.Input> tiers = request.tiers() == null
                ? List.of()
                : request.tiers().stream()
                        .map(entry -> new TierRules.Input(
                                entry.code(), entry.name(), entry.minSpend(), entry.earnMultiplier()))
                        .toList();
        ReplaceTiersUseCase.Result result = replace.execute(
                new ReplaceTiersUseCase.Command(tiers), Preconditions.required(ifMatch));
        return ResponseEntity.ok()
                .eTag(ExpectedVersion.etag(result.version()))
                .body(new TierListResponse(
                        result.tiers().stream()
                                .map(tier -> new TierResponse(tier.code(), tier.name(), tier.minSpend(), tier.earnMultiplier()))
                                .toList(),
                        result.version()));
    }
}
