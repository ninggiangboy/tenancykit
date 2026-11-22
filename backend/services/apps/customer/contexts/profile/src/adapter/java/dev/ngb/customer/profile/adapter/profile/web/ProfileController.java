package dev.ngb.customer.profile.adapter.profile.web;

import dev.ngb.core.security.Caller;
import dev.ngb.core.security.Callers;
import dev.ngb.core.security.RequiresPermission;
import dev.ngb.customer.activity.api.activity.ActivitySnapshot;
import dev.ngb.customer.customers.api.customer.ConsentState;
import dev.ngb.customer.customers.api.customer.CustomerProfile;
import dev.ngb.customer.orders.api.order.OrderLineSnapshot;
import dev.ngb.customer.orders.api.order.OrderSnapshot;
import dev.ngb.customer.orders.api.order.ReturnLineSnapshot;
import dev.ngb.customer.orders.api.order.ReturnSnapshot;
import dev.ngb.customer.profile.application.profile.query.ProfileQueries;
import dev.ngb.customer.profile.application.profile.query.ProfileView;
import dev.ngb.customer.shared.CustomerPermissions;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code GET /customers/{externalRef}/profile}: one customer read back as one document.
 *
 * <p>The document is composed of slices the contexts that own them hand over — the customer with its field values,
 * tags and consents, its latest orders with their lines and returns, and its latest activities — each bounded by the
 * request. Nothing here writes and nothing here aggregates, so reading a customer can neither change it nor turn into
 * a report.
 */
@Profile("api")
@RestController
@RequestMapping("/api/v1")
@Callers({Caller.TENANT_USER, Caller.TENANT_SYSTEM})
public class ProfileController {

    /** The current state of one consent topic: the status of its latest record and when that record happened. */
    public record ConsentRecord(String topicKey, String status, Instant occurredAt) {

        static ConsentRecord of(ConsentState state) {
            return new ConsentRecord(state.topicKey(), state.status(), state.occurredAt());
        }
    }

    /** The customer part of the profile: its attributes, its field values, its tags and what it currently agrees to. */
    public record CustomerResponse(
            String id,
            String externalRef,
            String displayName,
            String phone,
            String email,
            Map<String, Object> customFields,
            List<String> tags,
            List<ConsentRecord> consents,
            String status,
            Instant createdAt,
            Instant updatedAt) {

        static CustomerResponse of(CustomerProfile customer) {
            return new CustomerResponse(
                    customer.id().toString(),
                    customer.externalRef(),
                    customer.displayName(),
                    customer.phone(),
                    customer.email(),
                    customer.customFields(),
                    customer.tags(),
                    customer.consents().stream().map(ConsentRecord::of).toList(),
                    customer.status(),
                    customer.createdAt(),
                    customer.updatedAt());
        }
    }

    public record OrderLineResponse(
            int lineNo,
            String sku,
            String name,
            String category,
            BigDecimal quantity,
            long amount,
            boolean earnEligible,
            BigDecimal returnedQuantity,
            long returnedAmount) {

        static OrderLineResponse of(OrderLineSnapshot line) {
            return new OrderLineResponse(
                    line.lineNo(),
                    line.sku(),
                    line.name(),
                    line.category(),
                    line.quantity(),
                    line.amount(),
                    line.earnEligible(),
                    line.returnedQuantity(),
                    line.returnedAmount());
        }
    }

    public record ReturnLineResponse(int lineNo, BigDecimal quantity, long amount) {

        static ReturnLineResponse of(ReturnLineSnapshot line) {
            return new ReturnLineResponse(line.lineNo(), line.quantity(), line.amount());
        }
    }

    public record ReturnResponse(String returnRef, Instant occurredAt, List<ReturnLineResponse> lines) {

        static ReturnResponse of(ReturnSnapshot recorded) {
            return new ReturnResponse(
                    recorded.returnRef(),
                    recorded.occurredAt(),
                    recorded.lines().stream().map(ReturnLineResponse::of).toList());
        }
    }

    public record OrderResponse(
            String id,
            String orderRef,
            String customerId,
            String externalRef,
            String currency,
            long total,
            Instant occurredAt,
            String storeCode,
            String channel,
            String status,
            long orderVersion,
            Instant createdAt,
            List<OrderLineResponse> lines,
            List<ReturnResponse> returns) {

        static OrderResponse of(OrderSnapshot order, String customerId, String externalRef) {
            return new OrderResponse(
                    order.id().toString(),
                    order.orderRef(),
                    customerId,
                    externalRef,
                    order.currency(),
                    order.total(),
                    order.occurredAt(),
                    order.storeCode(),
                    order.channel(),
                    order.status(),
                    order.version(),
                    order.createdAt(),
                    order.lines().stream().map(OrderLineResponse::of).toList(),
                    order.returns().stream().map(ReturnResponse::of).toList());
        }
    }

    public record ActivityResponse(
            String id,
            String customerId,
            String externalRef,
            String type,
            Instant occurredAt,
            String source,
            Map<String, Object> payload,
            String activityRef,
            Instant createdAt) {

        static ActivityResponse of(ActivitySnapshot activity, String externalRef) {
            return new ActivityResponse(
                    activity.id().toString(),
                    activity.customerId().toString(),
                    externalRef,
                    activity.type(),
                    activity.occurredAt(),
                    activity.source(),
                    activity.payload(),
                    activity.activityRef(),
                    activity.createdAt());
        }
    }

    /** One customer as one document: the customer, and the bounded slices of what happened around it. */
    public record ProfileResponse(
            CustomerResponse customer,
            Map<String, Object> customFields,
            List<String> tags,
            List<ConsentRecord> consents,
            List<OrderResponse> orders,
            List<ActivityResponse> activities) {

        static ProfileResponse of(ProfileView view) {
            CustomerProfile customer = view.customer();
            String externalRef = customer.externalRef();
            String customerId = customer.id().toString();
            return new ProfileResponse(
                    CustomerResponse.of(customer),
                    customer.customFields(),
                    customer.tags(),
                    customer.consents().stream().map(ConsentRecord::of).toList(),
                    view.orders().stream()
                            .map(order -> OrderResponse.of(order, customerId, externalRef))
                            .toList(),
                    view.activities().stream()
                            .map(activity -> ActivityResponse.of(activity, externalRef))
                            .toList());
        }
    }

    private final ProfileQueries profiles;

    public ProfileController(ProfileQueries profiles) {
        this.profiles = profiles;
    }

    /**
     * @param orders how many of the customer's latest orders to include, 0 to 50, default 10
     * @param activities how many of its latest activities to include, 0 to 100, default 20
     */
    @GetMapping("/customers/{externalRef}/profile")
    @RequiresPermission(CustomerPermissions.CUSTOMER_READ)
    public ProfileResponse getProfile(
            @PathVariable String externalRef,
            @RequestParam(required = false) Integer orders,
            @RequestParam(required = false) Integer activities) {
        return ProfileResponse.of(profiles.read(externalRef, orders, activities));
    }
}
