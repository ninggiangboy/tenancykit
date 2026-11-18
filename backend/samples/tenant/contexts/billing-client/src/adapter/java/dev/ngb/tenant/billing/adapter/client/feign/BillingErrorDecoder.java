package dev.ngb.tenant.billing.adapter.client.feign;

import dev.ngb.core.feign.StatusFeignErrorMapper;
import dev.ngb.core.error.BadRequestException;
import dev.ngb.core.error.ConflictException;
import dev.ngb.core.error.AccessDeniedException;
import dev.ngb.core.error.NotFoundException;
import dev.ngb.core.error.PaymentRequiredException;
import dev.ngb.core.error.UnauthorizedException;
import dev.ngb.core.error.UpstreamUnavailableException;
import tools.jackson.databind.ObjectMapper;

/** Maps Billing's refusals onto the domain errors; 5xx and 429 stay retryable in the shared base. */
final class BillingErrorDecoder extends StatusFeignErrorMapper {

    BillingErrorDecoder(ObjectMapper json) {
        super("Billing", json);
    }

    @Override
    protected Exception refusal(int status, String detail) {
        return switch (status) {
            case 401 -> new UnauthorizedException("Billing rejected the credential");
            case 402 -> new PaymentRequiredException(detail);
            case 403 -> new AccessDeniedException("Billing refused this operation for the credential");
            case 404 -> new NotFoundException(detail);
            case 409 -> new ConflictException(detail);
            default -> status >= 400 && status < 500
                    ? new BadRequestException(detail)
                    : new UpstreamUnavailableException("Billing answered " + status + ": " + detail);
        };
    }
}
