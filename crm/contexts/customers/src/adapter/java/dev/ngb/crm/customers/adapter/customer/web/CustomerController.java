package dev.ngb.crm.customers.adapter.customer.web;

import dev.ngb.core.error.IdempotencyConflictException;
import dev.ngb.core.http.HttpHeaders;
import dev.ngb.core.idempotency.RepeatableCreate;
import dev.ngb.core.query.Page;
import dev.ngb.core.security.RequiresPermission;
import dev.ngb.core.version.ConditionalUpdate;
import dev.ngb.core.version.ExpectedVersion;
import dev.ngb.crm.customers.application.customer.command.PatchCustomerUseCase;
import dev.ngb.crm.customers.application.customer.command.UpsertCustomerUseCase;
import dev.ngb.crm.customers.application.customer.exception.IfMatchRequiredException;
import dev.ngb.crm.customers.application.customer.query.CustomerQueries;
import dev.ngb.crm.customers.application.customer.query.CustomerView;
import dev.ngb.crm.customers.application.customer.service.CustomerRules;
import dev.ngb.crm.customers.domain.customer.Customer;
import dev.ngb.crm.shared.CrmPermissions;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The Tenant's customers, keyed by the reference its own system chose.
 *
 * <p>{@code PUT} replaces a customer and answers {@code 201} the first time and {@code 200} afterwards, including when
 * the body changed nothing. {@code PATCH} is conditional on {@code If-Match}, so two systems that edit one customer
 * cannot overwrite each other silently. Every response carries the version as an {@code ETag}.
 *
 * <p>Both bodies carry the customer's field values and tags, because a caller states and reads the customer as one
 * representation; the field definitions and the consent records that give them meaning have endpoints of their own.
 */
@Profile("api")
@RestController
@RequestMapping("/api/v1")
public class CustomerController {

    /**
     * The representation a PUT stores. A null attribute is one the Tenant does not have, and the field values and tags
     * replace the stored ones.
     */
    public record CustomerUpsertRequest(
            String externalRef,
            String displayName,
            String phone,
            String email,
            Map<String, Object> customFields,
            List<String> tags) {}

    /**
     * The properties to change: a null one is left as it is. Field values merge into the stored set, and the tags to add
     * and remove change the tag set without replacing it.
     */
    public record CustomerPatchRequest(
            String displayName,
            String phone,
            String email,
            Map<String, Object> customFields,
            List<String> addTags,
            List<String> removeTags) {}

    public record CustomerResponse(
            String id,
            String externalRef,
            String displayName,
            String phone,
            String email,
            Map<String, Object> customFields,
            List<String> tags,
            String status,
            Instant createdAt,
            Instant updatedAt) {

        static CustomerResponse of(Customer customer) {
            return new CustomerResponse(
                    customer.id().toString(),
                    customer.externalRef(),
                    customer.displayName(),
                    customer.phone(),
                    customer.email(),
                    customer.fields(),
                    customer.tagList(),
                    customer.status().name(),
                    customer.createdAt(),
                    customer.updatedAt());
        }

        static CustomerResponse ofView(CustomerView view) {
            return new CustomerResponse(
                    view.id().toString(),
                    view.externalRef(),
                    view.displayName(),
                    view.phone(),
                    view.email(),
                    view.customFields(),
                    view.tags(),
                    view.status().name(),
                    view.createdAt(),
                    view.updatedAt());
        }
    }

    private final UpsertCustomerUseCase upsert;
    private final PatchCustomerUseCase patches;
    private final CustomerQueries customerQueries;

    public CustomerController(
            UpsertCustomerUseCase upsert, PatchCustomerUseCase patches, CustomerQueries customerQueries) {
        this.upsert = upsert;
        this.patches = patches;
        this.customerQueries = customerQueries;
    }

    /**
     * Creates the customer, or replaces it when the reference already exists. A repeated identical body changes
     * nothing, bumps no version and announces nothing.
     */
    @PutMapping("/customers/{externalRef}")
    @RepeatableCreate
    @RequiresPermission(CrmPermissions.CUSTOMER_MANAGE)
    public ResponseEntity<CustomerResponse> putCustomer(
            @PathVariable String externalRef,
            @RequestHeader(value = HttpHeaders.REQUEST_ID, required = false) String requestId,
            @RequestBody CustomerUpsertRequest request) {
        requireSameReference(externalRef, request.externalRef());
        UpsertCustomerUseCase.Result result = upsert.execute(new UpsertCustomerUseCase.Command(
                externalRef,
                request.displayName(),
                request.phone(),
                request.email(),
                request.customFields(),
                request.tags()));
        return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK)
                .eTag(ExpectedVersion.etag(result.customer().version()))
                .body(CustomerResponse.of(result.customer()));
    }

    /** Changes only the properties that are present; the version the caller pinned must still be the current one. */
    @PatchMapping("/customers/{externalRef}")
    @ConditionalUpdate
    @RequiresPermission(CrmPermissions.CUSTOMER_MANAGE)
    public ResponseEntity<CustomerResponse> patchCustomer(
            @PathVariable String externalRef,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            @RequestHeader(value = HttpHeaders.REQUEST_ID, required = false) String requestId,
            @RequestBody CustomerPatchRequest request) {
        Customer customer = patches.execute(
                externalRef,
                new PatchCustomerUseCase.Change(
                        request.displayName(),
                        request.phone(),
                        request.email(),
                        request.customFields(),
                        request.addTags(),
                        request.removeTags()),
                required(ifMatch));
        return ResponseEntity.ok()
                .eTag(ExpectedVersion.etag(customer.version()))
                .body(CustomerResponse.of(customer));
    }

    @GetMapping("/customers/{externalRef}")
    @RequiresPermission(CrmPermissions.CUSTOMER_READ)
    public ResponseEntity<CustomerResponse> getCustomer(@PathVariable String externalRef) {
        CustomerView customer = customerQueries.get(externalRef);
        return ResponseEntity.ok()
                .eTag(ExpectedVersion.etag(customer.version()))
                .body(CustomerResponse.ofView(customer));
    }

    /**
     * The list contract's paging and sort, narrowed by a search term, the tags the customer must carry, its field
     * values, its current consent state and a window of when it was created.
     */
    @GetMapping("/customers")
    @RequiresPermission(CrmPermissions.CUSTOMER_READ)
    public Page<CustomerResponse> listCustomers(@RequestParam MultiValueMap<String, String> params) {
        CustomerSearchQuery.Parsed parsed = CustomerSearchQuery.parse(params);
        return customerQueries.search(parsed.listing(), parsed.criteria()).map(CustomerResponse::ofView);
    }

    /** The reference is the natural key and is immutable, so a body naming another one would write to a second customer. */
    private static void requireSameReference(String pathRef, String bodyRef) {
        if (bodyRef == null || bodyRef.isBlank()) {
            return;
        }
        String path = CustomerRules.externalRef(pathRef);
        String body = CustomerRules.externalRef(bodyRef);
        if (!path.equals(body)) {
            throw new IdempotencyConflictException("customer", path);
        }
    }

    /** A conditional change without its version would silently overwrite another caller's, so it answers 428. */
    private static ExpectedVersion required(String ifMatch) {
        if (ifMatch == null || ifMatch.isBlank()) {
            throw new IfMatchRequiredException();
        }
        return ExpectedVersion.fromIfMatch(ifMatch);
    }
}
