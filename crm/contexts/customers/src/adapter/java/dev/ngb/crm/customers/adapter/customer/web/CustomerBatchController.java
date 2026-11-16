package dev.ngb.crm.customers.adapter.customer.web;

import dev.ngb.core.http.HttpHeaders;
import dev.ngb.core.security.RequiresPermission;
import dev.ngb.crm.customers.application.customer.command.UpsertCustomerUseCase;
import dev.ngb.crm.customers.application.customer.command.UpsertCustomersBatchUseCase;
import dev.ngb.crm.shared.CrmPermissions;
import java.util.List;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code POST /customers/batch}: many customers in one call, each applied in its own transaction and reported on its
 * own. The response is {@code 200} even when items failed, because a caller's backfill must not restart from the
 * beginning over one bad item; only a wrong envelope fails the call.
 */
@Profile("api")
@RestController
@RequestMapping("/api/v1")
public class CustomerBatchController {

    public record BatchCustomersRequest(List<CustomerController.CustomerUpsertRequest> items) {}

    /** One item's result, keyed by its position and its reference, so a caller can match results to what it sent. */
    public record BatchItemResult(int index, String ref, String outcome, String code) {}

    public record BatchCustomersResponse(List<BatchItemResult> results) {}

    private final UpsertCustomersBatchUseCase batch;

    public CustomerBatchController(UpsertCustomersBatchUseCase batch) {
        this.batch = batch;
    }

    @PostMapping("/customers/batch")
    @RequiresPermission(CrmPermissions.CUSTOMER_MANAGE)
    public BatchCustomersResponse upsertCustomers(
            @RequestHeader(value = HttpHeaders.REQUEST_ID, required = false) String requestId,
            @RequestBody(required = false) BatchCustomersRequest request) {
        List<UpsertCustomerUseCase.Command> commands =
                request == null || request.items() == null ? null : request.items().stream()
                        .map(CustomerBatchController::command)
                        .toList();
        List<UpsertCustomersBatchUseCase.Result> results = batch.execute(commands);
        List<BatchItemResult> items = new java.util.ArrayList<>(results.size());
        for (int index = 0; index < results.size(); index++) {
            UpsertCustomersBatchUseCase.Result result = results.get(index);
            items.add(new BatchItemResult(index, result.externalRef(), result.outcome(), result.code()));
        }
        return new BatchCustomersResponse(List.copyOf(items));
    }

    /** A null item is a malformed one, not a reason to fail the batch: it carries no reference and fails alone. */
    private static UpsertCustomerUseCase.Command command(CustomerController.CustomerUpsertRequest item) {
        if (item == null) {
            return new UpsertCustomerUseCase.Command(null, null, null, null);
        }
        return new UpsertCustomerUseCase.Command(
                item.externalRef(), item.displayName(), item.phone(), item.email());
    }
}
