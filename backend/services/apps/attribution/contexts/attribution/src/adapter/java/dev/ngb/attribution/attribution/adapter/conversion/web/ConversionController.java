package dev.ngb.attribution.attribution.adapter.conversion.web;

import dev.ngb.attribution.attribution.application.conversion.command.RecordConversionUseCase;
import dev.ngb.attribution.attribution.application.conversion.port.ConversionRepository;
import dev.ngb.attribution.attribution.application.conversion.query.ConversionListing;
import dev.ngb.attribution.attribution.application.conversion.query.ConversionQueries;
import dev.ngb.attribution.attribution.application.conversion.query.ConversionView;
import dev.ngb.attribution.attribution.domain.credit.CreditEngine;
import dev.ngb.attribution.attribution.domain.conversion.Conversion;
import dev.ngb.attribution.shared.AttributionPermissions;
import dev.ngb.core.error.BadRequestException;
import dev.ngb.core.error.NotFoundException;
import dev.ngb.core.query.ListQuery;
import dev.ngb.core.query.Page;
import dev.ngb.core.runtime.UnitOfWork;
import dev.ngb.core.security.Caller;
import dev.ngb.core.security.Callers;
import dev.ngb.core.security.RequiresPermission;
import dev.ngb.core.spring.query.Listing;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The postback and the conversion read side.
 *
 * <p>A postback is the one endpoint a Tenant's own order flow calls, so it answers {@code 201} on the first call and
 * {@code 200} on a replay of the same key and body; a forged click token is stored unattributed rather than rejected,
 * because the Tenant's flow must not fail. Reading a conversion shows its chain and credits.
 */
@Tag(
        name = "Conversions",
        description =
                "The postback a tenant's order flow calls and the read side of conversions with their credits.")
@RestController
@RequestMapping("/api/v1/conversions")
@Callers({Caller.TENANT_USER, Caller.TENANT_SYSTEM})
public class ConversionController {

    private final RecordConversionUseCase record;
    private final ConversionQueries queries;
    private final ConversionRepository conversions;
    private final UnitOfWork unitOfWork;

    public ConversionController(
            RecordConversionUseCase record,
            ConversionQueries queries,
            ConversionRepository conversions,
            UnitOfWork unitOfWork) {
        this.record = record;
        this.queries = queries;
        this.conversions = conversions;
        this.unitOfWork = unitOfWork;
    }

    @Operation(
            summary = "Record a conversion",
            description =
                    "The postback a tenant's own order flow calls when a goal (`goalKey`) was reached, with the `clickId` "
                    + "or `visitorId`, the `value`, `currency` and `occurredAt`. It answers `201` the first time and `200` "
                    + "on a replay of the same `Idempotency-Key` and body. A forged or foreign click token is stored as "
                    + "`UNATTRIBUTED` rather than rejected, because the tenant's order flow must not fail when its own "
                    + "reporting is imperfect; a request with neither a click nor a visitor is `400`. Requires "
                    + "`attribution:conversion:write`.")
    @PostMapping
    @RequiresPermission(AttributionPermissions.CONVERSION_WRITE)
    ResponseEntity<ConversionResponse> create(
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody ConversionRequest request) {
        RecordConversionUseCase.Result result = record.execute(new RecordConversionUseCase.Command(
                request.goalKey(),
                request.clickId(),
                request.visitorId(),
                decimal(request.value()),
                request.currency(),
                request.occurredAt(),
                idempotencyKey));
        return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK)
                .body(ConversionResponse.of(result.conversion(), creditsOf(result.conversion().id())));
    }

    /** Filter, sort and paging follow the list-query contract; the documented parameters come from {@link ConversionListing}. */
    @Operation(
            summary = "List conversions",
            description =
                    "Pages through the tenant's conversions with their credits, with the list-query contract's filtering, "
                    + "sorting and paging.")
    @GetMapping
    @Listing(ConversionListing.class)
    @RequiresPermission(AttributionPermissions.REPORT_READ)
    Page<ConversionResponse> list(ListQuery query) {
        return queries.list(query).map(ConversionResponse::ofView);
    }

    @Operation(
            summary = "Get a conversion",
            description =
                    "Returns one conversion with its chain of touches and the credit each campaign received. An unknown "
                    + "id answers `404`.")
    @GetMapping("/{id}")
    @RequiresPermission(AttributionPermissions.REPORT_READ)
    ConversionResponse get(@PathVariable UUID id) {
        Conversion conversion = unitOfWork.transactional(
                () -> conversions.findById(id).orElseThrow(() -> new NotFoundException("No such conversion.")));
        return ConversionResponse.of(conversion, creditsOf(id));
    }

    private List<CreditEngine.Credit> creditsOf(UUID conversionId) {
        return unitOfWork.transactional(() -> conversions.credits(conversionId));
    }

    private static BigDecimal decimal(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return new BigDecimal(value.strip());
        } catch (NumberFormatException e) {
            throw new BadRequestException("A conversion value is a decimal.");
        }
    }

    /**
     * The postback body. {@code click_id} is the signed token the redirect appended; the contract names it
     * {@code click_id} and this endpoint verifies it before trusting the click it names.
     */
    record ConversionRequest(
            String goalKey,
            String clickId,
            String visitorId,
            String value,
            String currency,
            Instant occurredAt) {}

    record ConversionResponse(
            UUID id,
            UUID goalId,
            String value,
            String currency,
            Instant occurredAt,
            String source,
            String creditStatus,
            UUID owningCampaignId,
            List<ChainTouchResponse> touchChain,
            List<CreditResponse> credits) {

        static ConversionResponse ofView(ConversionView view) {
            return new ConversionResponse(
                    view.id(),
                    view.goalId(),
                    view.value().toPlainString(),
                    view.currency(),
                    view.occurredAt(),
                    view.source(),
                    view.creditStatus(),
                    view.owningCampaignId(),
                    List.of(),
                    view.credits().stream()
                            .map(credit -> new CreditResponse(credit.clickId(), credit.credit()))
                            .toList());
        }

        static ConversionResponse of(Conversion conversion, List<CreditEngine.Credit> credits) {
            return new ConversionResponse(
                    conversion.id(),
                    conversion.goalId(),
                    conversion.value().toPlainString(),
                    conversion.currency(),
                    conversion.occurredAt(),
                    conversion.source().name(),
                    conversion.creditStatus().name(),
                    conversion.owningCampaignId(),
                    conversion.touchChain().stream()
                            .map(touch -> new ChainTouchResponse(
                                    touch.clickId(), touch.linkId(), touch.campaignId(), touch.at()))
                            .toList(),
                    credits.stream()
                            .map(credit -> new CreditResponse(credit.clickId(), credit.credit().toPlainString()))
                            .toList());
        }
    }

    record ChainTouchResponse(String clickId, String linkId, String campaignId, Instant at) {}

    record CreditResponse(String clickId, String credit) {}
}
