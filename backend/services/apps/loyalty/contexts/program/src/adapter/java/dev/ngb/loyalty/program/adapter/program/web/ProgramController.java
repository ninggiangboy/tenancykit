package dev.ngb.loyalty.program.adapter.program.web;

import dev.ngb.core.http.HttpHeaders;
import dev.ngb.core.security.Caller;
import dev.ngb.core.security.Callers;
import dev.ngb.core.security.RequiresPermission;
import dev.ngb.core.version.ConditionalUpdate;
import dev.ngb.core.version.ExpectedVersion;
import dev.ngb.loyalty.program.api.program.ProgramView;
import dev.ngb.loyalty.program.application.program.command.SaveProgramUseCase;
import dev.ngb.loyalty.program.application.program.query.ProgramQueries;
import dev.ngb.loyalty.program.domain.program.Program;
import dev.ngb.loyalty.shared.LoyaltyPermissions;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The tenant's one points program. A read before the first write answers {@code 404 PROGRAM_NOT_CONFIGURED}, because a
 * tenant without a program is a tenant in which nothing earns.
 *
 * <p>The write is a full replace and needs {@code If-Match}: the first call sends {@code *} to create, every later one
 * sends the ETag it read.
 */
@Profile("api")
@RestController
@RequestMapping("/api/v1")
@Callers({Caller.TENANT_USER, Caller.TENANT_SYSTEM})
public class ProgramController {

    /** The contract's names for the program's fields: {@code currencyUnit} is the minor units that earn one point. */
    public record ProgramRequest(
            String currency,
            Long currencyUnit,
            Integer expiryMonths,
            Integer expiryNoticeDays,
            Integer tierWindowMonths,
            String timeZone) {}

    public record ProgramResponse(
            String currency,
            long currencyUnit,
            int expiryMonths,
            int expiryNoticeDays,
            int tierWindowMonths,
            String timeZone,
            long version) {

        static ProgramResponse of(Program program) {
            return new ProgramResponse(
                    program.currency(),
                    program.unitAmount(),
                    program.expiryMonths(),
                    program.expiryNoticeDays(),
                    program.tierWindowMonths(),
                    program.timeZone().getId(),
                    program.version());
        }

        static ProgramResponse ofView(ProgramView view) {
            return new ProgramResponse(
                    view.currency(),
                    view.unitAmount(),
                    view.expiryMonths(),
                    view.expiryNoticeDays(),
                    view.tierWindowMonths(),
                    view.timeZone().getId(),
                    view.version());
        }
    }

    private final SaveProgramUseCase save;
    private final ProgramQueries queries;

    public ProgramController(SaveProgramUseCase save, ProgramQueries queries) {
        this.save = save;
        this.queries = queries;
    }

    @GetMapping("/program")
    @RequiresPermission(LoyaltyPermissions.PROGRAM_READ)
    public ResponseEntity<ProgramResponse> getProgram() {
        ProgramView program = queries.get();
        return ResponseEntity.ok()
                .eTag(ExpectedVersion.etag(program.version()))
                .body(ProgramResponse.ofView(program));
    }

    @Callers(Caller.TENANT_USER)
    @PutMapping("/program")
    @RequiresPermission(LoyaltyPermissions.PROGRAM_MANAGE)
    @ConditionalUpdate
    public ResponseEntity<ProgramResponse> putProgram(
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            @RequestHeader(value = HttpHeaders.REQUEST_ID, required = false) String requestId,
            @RequestBody ProgramRequest request) {
        Program program = save.execute(
                new SaveProgramUseCase.Command(
                        request.currency(),
                        request.currencyUnit(),
                        request.expiryMonths(),
                        request.expiryNoticeDays(),
                        request.tierWindowMonths(),
                        request.timeZone()),
                Preconditions.required(ifMatch));
        return ResponseEntity.ok()
                .eTag(ExpectedVersion.etag(program.version()))
                .body(ProgramResponse.of(program));
    }
}
