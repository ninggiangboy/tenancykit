package dev.ngb.loyalty.points.adapter.member.web;

import dev.ngb.core.security.Caller;
import dev.ngb.core.security.Callers;
import dev.ngb.core.security.RequiresPermission;
import dev.ngb.loyalty.points.application.member.command.EraseMemberUseCase;
import dev.ngb.loyalty.shared.LoyaltyPermissions;
import java.time.Instant;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The erasure endpoint: one call clears the person this tenant's loyalty holds and answers what it left behind.
 *
 * <p>The answer is the same shape whether the call erased the data or replayed an erasure performed before, so a caller
 * that retries after a timeout reads the same result rather than a second erasure. The body carries loyalty's own id and
 * the instant and no attribute of the member: a response that repeated what it erased would undo the point of the call,
 * and the reference the caller sent appears in it nowhere.
 */
@Profile("api")
@RestController
@RequestMapping("/api/v1")
@Callers({Caller.TENANT_USER, Caller.TENANT_SYSTEM})
public class ErasureController {

    /** @param vouchersVoided the member's unused vouchers that the erasure stopped */
    public record ErasureResponse(UUID memberId, Instant erasedAt, long vouchersVoided) {}

    private final EraseMemberUseCase erasures;

    public ErasureController(EraseMemberUseCase erasures) {
        this.erasures = erasures;
    }

    @PostMapping("/members/{externalRef}/erasure")
    @RequiresPermission(LoyaltyPermissions.MEMBER_ERASE)
    public ErasureResponse eraseMember(@PathVariable String externalRef) {
        EraseMemberUseCase.Result result = erasures.execute(externalRef);
        return new ErasureResponse(result.memberId(), result.erasedAt(), result.vouchersVoided());
    }
}
