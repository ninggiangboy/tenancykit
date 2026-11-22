package dev.ngb.customer.customers.adapter.customer.web;

import dev.ngb.core.security.RequiresPermission;
import dev.ngb.customer.customers.application.customer.command.EraseCustomerUseCase;
import dev.ngb.customer.shared.CustomerPermissions;
import java.time.Instant;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The erasure endpoint: one call clears the person this tenant's customer holds and answers what it left behind.
 *
 * <p>The answer is the same shape whether the call erased the data or replayed an erasure performed before, so a
 * caller that retries after a timeout reads the same result rather than a second erasure. The body carries customer's own
 * id and the instant and no attribute of the person: a response that repeated what it erased would undo the point of
 * the call, and the reference the caller sent appears in it nowhere.
 */
@Profile("api")
@RestController
@RequestMapping("/api/v1")
public class ErasureController {

    /** @param outcome {@code ERASED} for the call that erased the data, {@code REPLAYED} for a retry of that call */
    public record ErasureResponse(String customerId, Instant erasedAt, String outcome) {}

    private final EraseCustomerUseCase erasures;

    public ErasureController(EraseCustomerUseCase erasures) {
        this.erasures = erasures;
    }

    @PostMapping("/customers/{externalRef}/erasure")
    @RequiresPermission(CustomerPermissions.CUSTOMER_ERASE)
    public ErasureResponse eraseCustomer(@PathVariable String externalRef) {
        EraseCustomerUseCase.Result result = erasures.execute(externalRef);
        return new ErasureResponse(
                result.customerId().toString(), result.erasedAt(), result.outcome().name());
    }
}
