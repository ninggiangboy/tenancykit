package dev.ngb.tenant.resource.adapter.order.web;

import dev.ngb.core.http.HttpHeaders;
import dev.ngb.tenant.identity.api.security.AuthenticatedUser;
import dev.ngb.tenant.identity.api.security.Role;
import dev.ngb.tenant.identity.api.security.TenantPermissions;
import dev.ngb.core.security.RequiresPermission;
import dev.ngb.tenant.resource.application.order.command.CreateOrderUseCase;
import dev.ngb.tenant.resource.application.order.command.ManageOrderUseCase;
import dev.ngb.tenant.resource.application.order.query.OrderQueries;
import dev.ngb.tenant.resource.application.order.query.OrderView;
import dev.ngb.tenant.resource.domain.order.Order;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Resource orders: estimate, reserve, then queue. */
@RestController
@RequestMapping("/api/orders")
public class OrderController {

    public record CreateOrderRequest(UUID planId, String quantity, Long events) {}

    public record OrderResponse(
            UUID id,
            String reference,
            UUID planId,
            String billingPlanId,
            String status,
            String walletId,
            String reservationId,
            long estimatedMinorUnits,
            String currency,
            String failureReason,
            Instant createdAt,
            Instant updatedAt) {
        static OrderResponse of(OrderView order) {
            return new OrderResponse(
                    order.id(),
                    order.reference(),
                    order.planId(),
                    order.billingPlanId(),
                    order.status().name(),
                    order.walletId(),
                    order.reservationId(),
                    order.estimatedMinorUnits(),
                    order.currency(),
                    order.failureReason(),
                    order.createdAt(),
                    order.updatedAt());
        }

        static OrderResponse of(Order order) {
            return new OrderResponse(
                    order.id(),
                    order.reference(),
                    order.catalogPlanId(),
                    order.billingPlanId(),
                    order.status().name(),
                    order.walletId(),
                    order.reservationId(),
                    order.estimatedMinorUnits(),
                    order.currency(),
                    order.failureReason(),
                    order.createdAt(),
                    order.updatedAt());
        }
    }

    private final CreateOrderUseCase create;
    private final OrderQueries queries;
    private final ManageOrderUseCase manage;

    public OrderController(CreateOrderUseCase create, OrderQueries queries, ManageOrderUseCase manage) {
        this.create = create;
        this.queries = queries;
        this.manage = manage;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @RequiresPermission(TenantPermissions.ORDER_CREATE)
    public OrderResponse create(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @RequestHeader(value = HttpHeaders.IDEMPOTENCY_KEY, required = false) String idempotencyKey,
            @RequestBody CreateOrderRequest request) {
        Order order = create.execute(new CreateOrderUseCase.Command(
                principal.id(), request.planId(), request.quantity(), request.events(), idempotencyKey));
        return OrderResponse.of(order);
    }

    @GetMapping
    @RequiresPermission(TenantPermissions.ORDER_READ)
    public List<OrderResponse> list(@AuthenticationPrincipal AuthenticatedUser principal) {
        List<OrderView> orders = principal.role() == Role.ADMIN ? queries.all() : queries.forUser(principal.id());
        return orders.stream().map(OrderResponse::of).toList();
    }

    @GetMapping("/{id}")
    @RequiresPermission(TenantPermissions.ORDER_READ)
    public OrderResponse get(@AuthenticationPrincipal AuthenticatedUser principal, @PathVariable UUID id) {
        return OrderResponse.of(queries.forUser(id, principal.id(), principal.role() == Role.ADMIN));
    }

    @PostMapping("/{id}/cancel")
    @RequiresPermission(TenantPermissions.ORDER_MANAGE)
    public OrderResponse cancel(@AuthenticationPrincipal AuthenticatedUser principal, @PathVariable UUID id) {
        return OrderResponse.of(manage.cancel(id, principal.id(), principal.role() == Role.ADMIN));
    }
}
