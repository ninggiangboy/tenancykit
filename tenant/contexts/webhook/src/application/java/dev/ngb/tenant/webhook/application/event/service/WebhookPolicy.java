package dev.ngb.tenant.webhook.application.event.service;

import dev.ngb.core.event.SuiteEventTypes;
import dev.ngb.core.runtime.Clock;
import dev.ngb.tenant.resource.api.execution.ExecutionOrders;
import dev.ngb.tenant.webhook.api.event.WebhookDecision;
import dev.ngb.tenant.webhook.api.event.WebhookEnvelope;
import dev.ngb.tenant.webhook.application.event.port.CustomerPeriods;
import dev.ngb.tenant.webhook.application.event.port.DebtBook;
import java.time.Instant;
import java.util.Map;

/**
 * The tenant decides what a Billing fact means for it. This reference policy records debt on a
 * failed payment, suspends resources when a subscription period cannot be paid, and resumes them
 * with an extended period when it is paid. Billing never imposes any of this.
 */
public class WebhookPolicy {

    private final DebtBook debts;
    private final CustomerPeriods periods;
    private final ExecutionOrders orders;
    private final Clock clock;

    public WebhookPolicy(DebtBook debts, CustomerPeriods periods, ExecutionOrders orders, Clock clock) {
        this.debts = debts;
        this.periods = periods;
        this.orders = orders;
        this.clock = clock;
    }

    public WebhookDecision apply(WebhookEnvelope event) {
        Instant now = clock.now();
        return switch (event.type()) {
            case SuiteEventTypes.PAYMENT_FAILED -> recordDebt(event, now);
            case SuiteEventTypes.INVOICE_PAID -> clearDebt(event, now);
            case SuiteEventTypes.SUBSCRIPTION_PAYMENT_FAILED -> suspend(event, now);
            case SuiteEventTypes.SUBSCRIPTION_RENEWED -> extend(event, now);
            default -> WebhookDecision.CONTINUE;
        };
    }

    private WebhookDecision recordDebt(WebhookEnvelope event, Instant now) {
        String customerId = string(event.data(), "customerId");
        Map<String, Object> amount = map(event.data(), "amount");
        Long minorUnits = number(amount, "minorUnits");
        if (customerId == null || minorUnits == null) {
            return WebhookDecision.CONTINUE;
        }
        debts.recordDebt(customerId, minorUnits, string(amount, "currency"), string(event.data(), "reason"), now);
        return WebhookDecision.RECORD_DEBT;
    }

    private WebhookDecision clearDebt(WebhookEnvelope event, Instant now) {
        String customerId = string(event.data(), "customerId");
        if (customerId == null) {
            return WebhookDecision.CONTINUE;
        }
        debts.clearOpenDebts(customerId, now);
        return WebhookDecision.CLEAR_DEBT;
    }

    private WebhookDecision suspend(WebhookEnvelope event, Instant now) {
        String customerId = string(event.data(), "customerId");
        if (customerId == null) {
            return WebhookDecision.CONTINUE;
        }
        orders.suspendByBillingCustomer(customerId, now);
        return WebhookDecision.SUSPEND;
    }

    private WebhookDecision extend(WebhookEnvelope event, Instant now) {
        String customerId = string(event.data(), "customerId");
        Instant periodEnd = instant(event.data(), "periodEnd");
        if (customerId == null || periodEnd == null) {
            return WebhookDecision.CONTINUE;
        }
        orders.resumeByBillingCustomer(customerId, now);
        periods.extend(customerId, periodEnd, now);
        return WebhookDecision.EXTEND_PERIOD;
    }

    private static String string(Map<String, Object> data, String key) {
        Object value = data == null ? null : data.get(key);
        return value instanceof String text ? text : null;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Map<String, Object> data, String key) {
        Object value = data == null ? null : data.get(key);
        return value instanceof Map<?, ?> nested ? (Map<String, Object>) nested : Map.of();
    }

    private static Long number(Map<String, Object> data, String key) {
        Object value = data == null ? null : data.get(key);
        return value instanceof Number number ? number.longValue() : null;
    }

    private static Instant instant(Map<String, Object> data, String key) {
        String value = string(data, key);
        try {
            return value == null ? null : Instant.parse(value);
        } catch (java.time.format.DateTimeParseException e) {
            return null;
        }
    }
}
