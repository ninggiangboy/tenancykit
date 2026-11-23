package dev.ngb.loyalty.points.adapter.member.web;

import dev.ngb.core.query.ListQuery;
import dev.ngb.core.query.Page;
import dev.ngb.core.security.Caller;
import dev.ngb.core.security.Callers;
import dev.ngb.core.security.RequiresPermission;
import dev.ngb.core.spring.query.Listing;
import dev.ngb.loyalty.points.application.ledger.query.LedgerEntryView;
import dev.ngb.loyalty.points.application.ledger.query.LedgerListing;
import dev.ngb.loyalty.points.application.ledger.query.LedgerQueries;
import dev.ngb.loyalty.points.application.member.command.EnrolMemberUseCase;
import dev.ngb.loyalty.points.application.member.query.ExpiringPoints;
import dev.ngb.loyalty.points.application.member.query.MemberDetail;
import dev.ngb.loyalty.points.application.member.query.MemberQueries;
import dev.ngb.loyalty.points.application.member.query.MemberView;
import dev.ngb.loyalty.shared.LoyaltyPermissions;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Members of the Tenant's program: the enrolment that may come before the first order, the member with its computed
 * balance and expiring points, and its append-only ledger.
 *
 * <p>The reference is the caller's own key, so a member is addressed by it and never by loyalty's id.
 */
@Profile("api")
@RestController
@RequestMapping
@Callers({Caller.TENANT_USER, Caller.TENANT_SYSTEM})
public class MemberController {

    /** The optional profile of an enrolment; nothing else about a member is a caller's to set. */
    public record MemberUpsert(String displayName) {}

    /** A tier as a member read shows it; {@code remainingSpend} is the spend still missing for it, null on the one held. */
    public record TierResponse(String code, String name, long minSpend, BigDecimal earnMultiplier, Long remainingSpend) {

        static TierResponse of(MemberDetail.TierSummary tier) {
            return tier == null
                    ? null
                    : new TierResponse(
                            tier.code(), tier.name(), tier.minSpend(), tier.earnMultiplier(), tier.remainingSpend());
        }
    }

    public record ExpiringPointsResponse(Instant expiresAt, long points) {

        static ExpiringPointsResponse of(ExpiringPoints points) {
            return new ExpiringPointsResponse(points.expiresAt(), points.points());
        }
    }

    public record MemberResponse(
            UUID memberId,
            String externalRef,
            String displayName,
            long available,
            long debt,
            TierResponse tier,
            TierResponse nextTier,
            List<ExpiringPointsResponse> pointsExpiring,
            Instant enrolledAt,
            String status) {

        static MemberResponse of(MemberDetail detail) {
            MemberView member = detail.member();
            return new MemberResponse(
                    member.memberId(),
                    member.externalRef(),
                    member.displayName(),
                    member.available(),
                    member.debt(),
                    TierResponse.of(detail.tier()),
                    TierResponse.of(detail.nextTier()),
                    member.pointsExpiring().stream().map(ExpiringPointsResponse::of).toList(),
                    member.enrolledAt(),
                    member.status().name());
        }
    }

    public record LedgerEntryResponse(
            UUID entryId, String kind, long points, String orderRef, String returnRef, UUID redemptionId, Instant createdAt) {

        static LedgerEntryResponse of(LedgerEntryView entry) {
            return new LedgerEntryResponse(entry.entryId(), entry.kind(), entry.points(), entry.orderRef(),
                    entry.returnRef(), entry.redemptionId(), entry.createdAt());
        }
    }

    private final EnrolMemberUseCase enrol;
    private final MemberQueries members;
    private final LedgerQueries ledger;

    public MemberController(EnrolMemberUseCase enrol, MemberQueries members, LedgerQueries ledger) {
        this.enrol = enrol;
        this.members = members;
        this.ledger = ledger;
    }

    /** Enrols a member ahead of its first order: 201 when this call created it, 200 when it already existed. */
    @PutMapping("/members/{externalRef}")
    @RequiresPermission(LoyaltyPermissions.MEMBER_MANAGE)
    public ResponseEntity<MemberResponse> putMember(
            @PathVariable String externalRef, @RequestBody(required = false) MemberUpsert request) {
        EnrolMemberUseCase.Result enrolled =
                enrol.execute(new EnrolMemberUseCase.Command(externalRef, request == null ? null : request.displayName()));
        MemberDetail detail = members.get(enrolled.externalRef(), null);
        return ResponseEntity.status(enrolled.created() ? HttpStatus.CREATED : HttpStatus.OK)
                .body(MemberResponse.of(detail));
    }

    @GetMapping("/members/{externalRef}")
    @RequiresPermission(LoyaltyPermissions.MEMBER_READ)
    public MemberResponse getMember(
            @PathVariable String externalRef,
            @RequestParam(required = false) Integer expiringWithinDays) {
        return MemberResponse.of(members.get(externalRef, expiringWithinDays));
    }

    /** Filter, sort and paging follow the list-query contract; the documented parameters come from {@link LedgerListing}. */
    @GetMapping("/members/{externalRef}/ledger")
    @Listing(LedgerListing.class)
    @RequiresPermission(LoyaltyPermissions.MEMBER_READ)
    public Page<LedgerEntryResponse> getLedger(@PathVariable String externalRef, ListQuery query) {
        return ledger.list(externalRef, query).map(LedgerEntryResponse::of);
    }
}
