package dev.ngb.attribution.spend.adapter.entry.web;

import dev.ngb.attribution.shared.AttributionPermissions;
import dev.ngb.attribution.spend.application.entry.command.BulkSetSpendUseCase;
import dev.ngb.attribution.spend.application.entry.command.DeleteManualSpendUseCase;
import dev.ngb.attribution.spend.application.entry.command.SetManualSpendUseCase;
import dev.ngb.attribution.spend.application.entry.query.SpendQueries;
import dev.ngb.attribution.spend.application.entry.query.SpendView;
import dev.ngb.attribution.spend.domain.entry.SpendEntry;
import dev.ngb.core.security.Caller;
import dev.ngb.core.security.Callers;
import dev.ngb.core.security.RequiresPermission;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * A campaign's advertising cost: one manual day, a bulk set, a delete that removes only the manual row, and the rows
 * with their source so a double entry is visible.
 */
@Tag(
        name = "Campaign spend",
        description =
                "A campaign's advertising cost: set manual days, bulk set, delete a manual day and read the rows.")
@RestController
@RequestMapping("/api/v1/campaigns/{campaignId}/spend")
@Callers({Caller.TENANT_USER, Caller.TENANT_SYSTEM})
public class SpendController {

    private final SetManualSpendUseCase set;
    private final BulkSetSpendUseCase bulk;
    private final DeleteManualSpendUseCase delete;
    private final SpendQueries queries;

    public SpendController(
            SetManualSpendUseCase set,
            BulkSetSpendUseCase bulk,
            DeleteManualSpendUseCase delete,
            SpendQueries queries) {
        this.set = set;
        this.bulk = bulk;
        this.delete = delete;
        this.queries = queries;
    }

    @Operation(
            summary = "Set a campaign's spend for one day",
            description =
                    "Sets the manual advertising cost of the campaign on `day`, with optional impressions and clicks. A "
                    + "currency the campaign does not use is refused.")
    @PutMapping("/{day}")
    @RequiresPermission(AttributionPermissions.SPEND_MANAGE)
    SpendResponse set(
            @PathVariable UUID campaignId,
            @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate day,
            @RequestBody SpendRequest request) {
        return SpendResponse.of(set.execute(new SetManualSpendUseCase.Command(
                campaignId, day, decimal(request.amount()), request.currency(), request.impressions(),
                request.clicks())));
    }

    @Operation(
            summary = "Set a campaign's spend for several days",
            description =
                    "Sets the manual cost for up to 366 days of the campaign in one transaction. Requires "
                    + "`attribution:spend:manage`.")
    @PostMapping
    @RequiresPermission(AttributionPermissions.SPEND_MANAGE)
    List<SpendResponse> bulk(@PathVariable UUID campaignId, @RequestBody BulkRequest request) {
        return bulk.execute(new BulkSetSpendUseCase.Command(
                        campaignId,
                        request.days() == null
                                ? List.of()
                                : request.days().stream()
                                        .map(row -> new BulkSetSpendUseCase.DayAmount(
                                                row.day(), decimal(row.amount()), row.currency(), row.impressions(),
                                                row.clicks()))
                                        .toList()))
                .stream().map(SpendResponse::of).toList();
    }

    @Operation(
            summary = "Delete a campaign's manual spend for one day",
            description =
                    "Deletes only the manual cost of the campaign on `day`, leaving any imported row in place. Answers "
                    + "`204`.")
    @DeleteMapping("/{day}")
    @RequiresPermission(AttributionPermissions.SPEND_MANAGE)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void delete(
            @PathVariable UUID campaignId,
            @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate day) {
        delete.execute(campaignId, day);
    }

    @Operation(
            summary = "List a campaign's spend",
            description =
                    "Returns the campaign's cost rows with their source, so a double entry of a manual and an imported "
                    + "row is visible.")
    @GetMapping
    @RequiresPermission(AttributionPermissions.SPEND_READ)
    List<SpendView> list(
            @PathVariable UUID campaignId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        LocalDate today = LocalDate.now(java.time.ZoneOffset.UTC);
        return queries.list(campaignId, from == null ? today.minusDays(29) : from, to == null ? today : to);
    }

    private static BigDecimal decimal(String value) {
        return value == null || value.isBlank() ? null : new BigDecimal(value.strip());
    }

    /** @param amount a decimal string, so a client never rounds in a binary float before the server sees it */
    record SpendRequest(String amount, String currency, Long impressions, Long clicks) {}

    record BulkDay(LocalDate day, String amount, String currency, Long impressions, Long clicks) {}

    record BulkRequest(List<BulkDay> days) {}

    record SpendResponse(
            UUID id,
            UUID campaignId,
            LocalDate day,
            String source,
            BigDecimal amount,
            String currency,
            Long impressions,
            Long clicks) {

        static SpendResponse of(SpendEntry entry) {
            return new SpendResponse(
                    entry.id(), entry.campaignId(), entry.day(), entry.source().name(), entry.amount(),
                    entry.currency(), entry.impressions(), entry.clicks());
        }
    }
}
