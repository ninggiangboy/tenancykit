package dev.ngb.booking.gate.adapter.token.web;

import dev.ngb.booking.gate.application.token.command.IssueCustomerTokenUseCase;
import dev.ngb.booking.shared.BookingPermissions;
import dev.ngb.core.security.Caller;
import dev.ngb.core.security.Callers;
import dev.ngb.core.security.RequiresPermission;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * The Tenant System mints a customer token here. This is the only route that creates one; the browser only presents it
 * under {@code /p/}.
 */
@Profile("api")
@RestController
@RequestMapping
@Callers({Caller.TENANT_USER, Caller.TENANT_SYSTEM})
public class CustomerTokenController {

    public record IssueTokenRequest(String customerRef, List<String> scopes, UUID offeringId, Integer ttlSeconds) {}

    public record CustomerTokenResponse(String token, Instant expiresAt, String keyId) {}

    private final IssueCustomerTokenUseCase tokens;

    public CustomerTokenController(IssueCustomerTokenUseCase tokens) {
        this.tokens = tokens;
    }

    @PostMapping("/customer-tokens")
    @ResponseStatus(HttpStatus.CREATED)
    @RequiresPermission(BookingPermissions.TOKEN_ISSUE)
    public CustomerTokenResponse issue(@RequestBody IssueTokenRequest request) {
        IssueCustomerTokenUseCase.Result result = tokens.execute(new IssueCustomerTokenUseCase.Command(
                request.customerRef(), request.scopes(), request.offeringId(), request.ttlSeconds()));
        return new CustomerTokenResponse(result.token(), result.expiresAt(), result.keyId());
    }
}
