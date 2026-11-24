package dev.ngb.billing.customer.adapter.customer.web;

import dev.ngb.core.http.HttpHeaders;
import dev.ngb.core.security.Caller;
import dev.ngb.core.security.Callers;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.context.annotation.Profile;
import dev.ngb.billing.customer.application.customer.command.CreateCustomerUseCase;
import dev.ngb.billing.customer.application.customer.command.ManageCustomerUseCase;
import dev.ngb.billing.customer.application.customer.query.CustomerListing;
import dev.ngb.billing.customer.application.customer.query.CustomerQueries;
import dev.ngb.billing.customer.application.customer.query.CustomerView;
import dev.ngb.core.query.ListQuery;
import dev.ngb.core.query.Page;
import dev.ngb.core.spring.query.Listing;
import dev.ngb.billing.customer.application.customer.query.CustomerIdentityView;
import dev.ngb.billing.customer.domain.customer.Customer;
import dev.ngb.billing.customer.domain.customer.ExternalIdentity;
import dev.ngb.core.security.Actor;
import dev.ngb.core.security.CurrentActor;
import dev.ngb.billing.sharedkernel.security.BillingPermissions;
import dev.ngb.core.security.RequiresPermission;
import dev.ngb.core.audit.Audit;
import dev.ngb.core.version.ConditionalUpdate;
import dev.ngb.core.version.ExpectedVersion;
import dev.ngb.core.idempotency.RepeatableCreate;
import dev.ngb.billing.sharedkernel.id.CustomerId;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Customers of the current tenant, for both kinds of caller: a Tenant System (API key) maps its own users, a
 * Tenant User (OIDC) administers customers and names the tenant system explicitly.
 */
@Tag(
        name = "Customers",
        description =
                "Billing customers of the tenant, optionally mapped to a Tenant System's own users.")
@Profile("api")
@RestController
@RequestMapping("/customers")
@Callers({Caller.TENANT_USER, Caller.TENANT_SYSTEM})
public class CustomerController {

    public record CreateCustomerRequest(String tenantSystemId, String externalUserId, String displayName, String email) {}

    public record UpdateCustomerRequest(String displayName, String email) {}

    public record IdentityResponse(String tenantSystemId, String externalUserId) {
        static IdentityResponse of(CustomerIdentityView identity) {
            return new IdentityResponse(identity.tenantSystemId().toString(), identity.externalUserId());
        }

        static IdentityResponse of(ExternalIdentity identity) {
            return new IdentityResponse(identity.tenantSystemId().toString(), identity.externalUserId());
        }
    }

    public record CustomerResponse(String id, String displayName, String email, String status, Instant createdAt, List<IdentityResponse> identities) {

        static CustomerResponse of(Customer customer) {
            return of(customer.id().toString(), customer.displayName(), customer.email(), customer.status().name(), customer.createdAt(),
                    customer.identities().stream().map(IdentityResponse::of).toList());
        }

        static CustomerResponse ofView(CustomerView customer) {
            return of(customer.id().toString(), customer.displayName(), customer.email(), customer.status().name(), customer.createdAt(),
                    customer.identities().stream().map(IdentityResponse::of).toList());
        }

        private static CustomerResponse of(
                String id, String displayName, String email, String status, Instant createdAt, List<IdentityResponse> identities) {
            return new CustomerResponse(id, displayName, email, status, createdAt,
                    identities.stream().sorted(Comparator.comparing(IdentityResponse::externalUserId)).toList());
        }
    }

    private final CreateCustomerUseCase create;
    private final ManageCustomerUseCase manage;
    private final CustomerQueries queries;
    private final CurrentActor currentActor;

    public CustomerController(CreateCustomerUseCase create, ManageCustomerUseCase manage, CustomerQueries queries, CurrentActor currentActor) {
        this.create = create;
        this.manage = manage;
        this.queries = queries;
        this.currentActor = currentActor;
    }

    /** Safe to retry: the same Idempotency-Key returns the same customer (201 for new, 200 for replay); the same key with a different body is a 409. */
    @Operation(
            summary = "Create a customer",
            description =
                    "Creates a billing customer, optionally mapped to a Tenant System user through `tenantSystemId` and "
                    + "`externalUserId`. A Tenant System maps its own users; a Tenant User names the tenant system "
                    + "explicitly. Safe to retry: the same `Idempotency-Key` returns the same customer (`201` for a new "
                    + "one, `200` for a replay), and the same key with a different body answers `409`.")
    @PostMapping
    @RepeatableCreate
    @RequiresPermission(BillingPermissions.CUSTOMER_WRITE)
    public ResponseEntity<CustomerResponse> create(
            @RequestHeader(HttpHeaders.IDEMPOTENCY_KEY) String idempotencyKey,
            @RequestHeader(value = HttpHeaders.REQUEST_ID, required = false) String requestId,
            @RequestBody CreateCustomerRequest request) {
        Actor actor = currentActor.get();
        CreateCustomerUseCase.Result result = create.execute(new CreateCustomerUseCase.Command(
                currentActor.callerId(), request.tenantSystemId(), request.externalUserId(), request.displayName(), request.email(),
                idempotencyKey, Audit.of(actor.auditName(), requestId)));
        HttpStatus status = result.replayed() ? HttpStatus.OK : HttpStatus.CREATED;
        return ResponseEntity.status(status).body(CustomerResponse.of(result.customer()));
    }

    /** Filter, sort and paging follow the list-query contract; the documented parameters come from {@link CustomerListing}. */
    @Operation(
            summary = "List customers",
            description =
                    "Pages through the tenant's customers, with the list-query contract's filtering, sorting and paging. "
                    + "A Tenant System sees and acts on the customers mapped to it only.")
    @GetMapping
    @Listing(CustomerListing.class)
    @RequiresPermission(BillingPermissions.CUSTOMER_READ)
    public Page<CustomerResponse> list(ListQuery query) {
        return queries.list(query, currentActor.callerId()).map(CustomerResponse::ofView);
    }

    @Operation(
            summary = "Find a customer by external user id",
            description =
                    "Returns the customer mapped to `externalUserId`, which is how a Tenant System finds a customer by "
                    + "its own user reference. A Tenant User also passes `tenantSystemId`. An unmapped user answers `404`.")
    @GetMapping("/by-external-user/{externalUserId}")
    @RequiresPermission(BillingPermissions.CUSTOMER_READ)
    public CustomerResponse byExternalUser(@PathVariable String externalUserId, @RequestParam(required = false) String tenantSystemId) {
        return CustomerResponse.ofView(queries.getByExternalUser(currentActor.callerId(), tenantSystemId, externalUserId));
    }

    @Operation(
            summary = "Get a customer",
            description =
                    "Returns one customer; its version is also the response's `ETag`. An unknown id answers `404`.")
    @GetMapping("/{id}")
    @RequiresPermission(BillingPermissions.CUSTOMER_READ)
    public ResponseEntity<CustomerResponse> get(@PathVariable String id) {
        CustomerView customer = queries.get(CustomerId.of(id), currentActor.callerId());
        return ResponseEntity.ok().eTag(ExpectedVersion.etag(customer.version())).body(CustomerResponse.ofView(customer));
    }

    @Operation(
            summary = "Update a customer",
            description =
                    "Changes the customer's display name and email. Pass the current `ETag` in `If-Match`; a stale "
                    + "version is refused with `412`.")
    @PutMapping("/{id}")
    @RequiresPermission(BillingPermissions.CUSTOMER_WRITE)
    @ConditionalUpdate
    public ResponseEntity<CustomerResponse> update(
            @PathVariable String id,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            @RequestHeader(value = HttpHeaders.REQUEST_ID, required = false) String requestId,
            @RequestBody UpdateCustomerRequest request) {
        Actor actor = currentActor.get();
        Customer customer = manage.update(
                Audit.of(actor.auditName(), requestId), CustomerId.of(id), request.displayName(), request.email(),
                ExpectedVersion.fromIfMatch(ifMatch), currentActor.callerId());
        return ResponseEntity.ok().eTag(ExpectedVersion.etag(customer.version())).body(CustomerResponse.of(customer));
    }

    @Operation(
            summary = "Deactivate a customer",
            description =
                    "Deactivates the customer. Pass the current `ETag` in `If-Match`; a stale version is refused with "
                    + "`412`.")
    @PostMapping("/{id}/deactivate")
    @RequiresPermission(BillingPermissions.CUSTOMER_WRITE)
    @ConditionalUpdate
    public ResponseEntity<CustomerResponse> deactivate(
            @PathVariable String id,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            @RequestHeader(value = HttpHeaders.REQUEST_ID, required = false) String requestId) {
        Actor actor = currentActor.get();
        Customer customer = manage.deactivate(
                Audit.of(actor.auditName(), requestId), CustomerId.of(id), ExpectedVersion.fromIfMatch(ifMatch), currentActor.callerId());
        return ResponseEntity.ok().eTag(ExpectedVersion.etag(customer.version())).body(CustomerResponse.of(customer));
    }
}
