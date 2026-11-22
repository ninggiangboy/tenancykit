package dev.ngb.billing.payment.adapter.stripe.web;

import dev.ngb.core.http.HttpHeaders;
import dev.ngb.billing.payment.application.payment.service.PaymentSettings;
import dev.ngb.billing.payment.application.stripe.command.ManageStripeEventUseCase;
import dev.ngb.billing.payment.application.stripe.command.ReconcileStripeUseCase;
import dev.ngb.billing.payment.application.stripe.query.EventAttemptView;
import dev.ngb.billing.payment.application.stripe.query.StripeConfigurationQueries;
import dev.ngb.billing.payment.application.stripe.query.StripeConfigurationView;
import dev.ngb.billing.payment.application.stripe.query.StripeEventListing;
import dev.ngb.billing.payment.application.stripe.query.StripeEventQueries;
import dev.ngb.billing.payment.application.stripe.query.StripeEventView;
import dev.ngb.billing.payment.application.stripe.service.StripeConfigurationService;
import dev.ngb.billing.payment.domain.stripe.EventStatus;
import dev.ngb.billing.payment.domain.stripe.IntegrationMode;
import dev.ngb.billing.payment.domain.stripe.StripeEventId;
import dev.ngb.billing.payment.domain.stripe.StripeConfiguration;
import dev.ngb.billing.payment.domain.stripe.StripeConfigurationId;
import dev.ngb.core.security.Caller;
import dev.ngb.core.security.Callers;
import dev.ngb.core.security.CurrentActor;
import dev.ngb.billing.sharedkernel.security.BillingPermissions;
import dev.ngb.core.security.RequiresPermission;
import dev.ngb.core.audit.Audit;
import dev.ngb.core.query.ListQuery;
import dev.ngb.core.query.Page;
import dev.ngb.core.spring.query.Listing;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** The Tenant's Stripe connection and the history of Stripe webhook processing. Nothing here returns a secret: the read side only says whether a webhook secret is set. */
@Tag(
        name = "Stripe configuration",
        description =
                "The tenant's Stripe connection and the history of Stripe webhook processing. Nothing here returns a "
                + "secret.")
@Profile("api")
@RestController
@RequestMapping("/api/v1")
@Callers(Caller.TENANT_USER)
public class StripeController {

    /** Carries Stripe secrets, so it never prints them (a request logger would otherwise). */
    public record DirectRequest(String secretKey, String publishableKey, String webhookSecret) {
        @Override
        public String toString() {
            return "DirectRequest[redacted]";
        }
    }

    public record WebhookSecretRequest(String webhookSecret) {
        @Override
        public String toString() {
            return "WebhookSecretRequest[redacted]";
        }
    }

    public record ConnectRequest(String displayName, String contactEmail) {}

    public record ConfigurationResponse(
            String id, String mode, String accountId, String environment, String status, String publishableKey, boolean webhookSecretSet,
            boolean cardPaymentsActive, String webhookUrl, Instant createdAt, Instant updatedAt, Instant connectedAt, Instant lastVerifiedAt) {}

    /** {@code onboardingUrl} is a Stripe-hosted page; it is only present while the Tenant still has onboarding to finish. */
    public record ConnectionResponse(ConfigurationResponse configuration, String onboardingUrl) {}

    public record EventResponse(
            String id, String stripeEventId, String type, String status, int attempts, Instant nextAttemptAt, String lastError, Instant receivedAt,
            Instant processedAt) {
        static EventResponse ofView(StripeEventView e) {
            return new EventResponse(e.id().toString(), e.eventId(), e.type(), e.status().name(), e.attempts(), e.nextAttemptAt(), e.lastError(),
                    e.receivedAt(), e.processedAt());
        }
    }

    public record EventAttemptResponse(int attempt, String outcome, String detail, Instant attemptedAt) {
        static EventAttemptResponse ofView(EventAttemptView a) {
            return new EventAttemptResponse(a.attempt(), a.outcome(), a.detail(), a.attemptedAt());
        }
    }

    public record EventDetailResponse(EventResponse event, List<EventAttemptResponse> history) {}

    public record ReconcileResponse(int checked, int corrected, int mismatches) {}

    private final StripeConfigurationService configuration;
    private final StripeConfigurationQueries configurationQueries;
    private final ManageStripeEventUseCase eventAdmin;
    private final StripeEventQueries eventQueries;
    private final ReconcileStripeUseCase reconcile;
    private final PaymentSettings settings;
    private final CurrentActor currentActor;

    public StripeController(
            StripeConfigurationService configuration, StripeConfigurationQueries configurationQueries, ManageStripeEventUseCase eventAdmin,
            StripeEventQueries eventQueries, ReconcileStripeUseCase reconcile, PaymentSettings settings, CurrentActor currentActor) {
        this.configuration = configuration;
        this.configurationQueries = configurationQueries;
        this.eventAdmin = eventAdmin;
        this.eventQueries = eventQueries;
        this.reconcile = reconcile;
        this.settings = settings;
        this.currentActor = currentActor;
    }

    @Operation(
            summary = "Connect the tenant's own Stripe account",
            description =
                    "Replaces any current configuration with a Direct one built on the tenant's own Stripe keys "
                    + "(`secretKey`, `publishableKey` and `webhookSecret`). Stripe is asked first and the secrets are "
                    + "stored encrypted; they are never returned, and the configuration only says whether a webhook secret "
                    + "is set.")
    @PutMapping("/stripe/configuration/direct")
    @RequiresPermission(BillingPermissions.STRIPE_MANAGE)
    public ConfigurationResponse connectDirect(
            @RequestHeader(value = HttpHeaders.REQUEST_ID, required = false) String requestId, @RequestBody DirectRequest request) {
        return configurationResponse(configuration.connectDirect(Audit.of(currentActor.get().auditName(), requestId), request.secretKey(), request.publishableKey(), request.webhookSecret()));
    }

    @Operation(
            summary = "Set the Stripe webhook signing secret",
            description =
                    "Stores the signing secret of the webhook the tenant created in its own Stripe Dashboard. It is never "
                    + "returned.")
    @PutMapping("/stripe/configuration/webhook-secret")
    @RequiresPermission(BillingPermissions.STRIPE_MANAGE)
    public ConfigurationResponse setWebhookSecret(
            @RequestHeader(value = HttpHeaders.REQUEST_ID, required = false) String requestId, @RequestBody WebhookSecretRequest request) {
        return configurationResponse(configuration.setWebhookSecret(Audit.of(currentActor.get().auditName(), requestId), request.webhookSecret()));
    }

    @Operation(
            summary = "Start Stripe Connect onboarding",
            description =
                    "Starts linking a Stripe account through the platform's Connect application and answers the "
                    + "Stripe-hosted onboarding page the tenant finishes. Repeating it while onboarding is open answers a "
                    + "fresh link.")
    @PostMapping("/stripe/configuration/connect")
    @RequiresPermission(BillingPermissions.STRIPE_MANAGE)
    public ConnectionResponse startConnect(
            @RequestHeader(value = HttpHeaders.REQUEST_ID, required = false) String requestId, @RequestBody ConnectRequest request) {
        return connectionResponse(configuration.startConnect(Audit.of(currentActor.get().auditName(), requestId), request.displayName(), request.contactEmail()));
    }

    /** A new onboarding link, for when the earlier one has expired. */
    @Operation(
            summary = "Get a new Stripe onboarding link",
            description =
                    "Answers a fresh onboarding link, for when the earlier one has expired.")
    @PostMapping("/stripe/configuration/connect/link")
    @RequiresPermission(BillingPermissions.STRIPE_MANAGE)
    public ConnectionResponse onboardingLink() {
        return connectionResponse(configuration.onboardingLink());
    }

    /** Asks Stripe what the connected account can do now. Call it when the Tenant returns from onboarding. */
    @Operation(
            summary = "Refresh the Stripe account status",
            description =
                    "Asks Stripe what the connected account can do now. Call it when the tenant returns from onboarding.")
    @PostMapping("/stripe/configuration/refresh")
    @RequiresPermission(BillingPermissions.STRIPE_MANAGE)
    public ConfigurationResponse refresh(@RequestHeader(value = HttpHeaders.REQUEST_ID, required = false) String requestId) {
        return configurationResponse(configuration.refresh(Audit.of(currentActor.get().auditName(), requestId)));
    }

    @Operation(
            summary = "Get the Stripe configuration",
            description =
                    "Returns the tenant's Stripe connection: mode, account, environment, status, whether card payments "
                    + "are active and the webhook URL. No secret is returned.")
    @GetMapping("/stripe/configuration")
    @RequiresPermission(BillingPermissions.STRIPE_READ)
    public ConfigurationResponse getConfiguration() {
        return configurationResponse(configurationQueries.get());
    }

    @Operation(
            summary = "Disconnect Stripe",
            description =
                    "Removes the tenant's Stripe configuration. Answers `204`.")
    @DeleteMapping("/stripe/configuration")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @RequiresPermission(BillingPermissions.STRIPE_MANAGE)
    public void disconnect(@RequestHeader(value = HttpHeaders.REQUEST_ID, required = false) String requestId) {
        configuration.disconnect(Audit.of(currentActor.get().auditName(), requestId));
    }

    /** Newest received first by default; filter, sort and paging follow the list-query contract. */
    @Operation(
            summary = "List Stripe events",
            description =
                    "Pages through the Stripe webhook events received for the tenant, newest first by default, with the "
                    + "list-query contract's filtering, sorting and paging.")
    @GetMapping("/stripe/events")
    @Listing(StripeEventListing.class)
    @RequiresPermission(BillingPermissions.STRIPE_READ)
    public Page<EventResponse> events(ListQuery query) {
        return eventQueries.list(query).map(EventResponse::ofView);
    }

    @Operation(
            summary = "Get a Stripe event",
            description =
                    "Returns one received Stripe event with how its processing went. An unknown id answers `404`.")
    @GetMapping("/stripe/events/{id}")
    @RequiresPermission(BillingPermissions.STRIPE_READ)
    public EventDetailResponse event(@PathVariable String id) {
        return eventDetail(eventQueries.get(UUID.fromString(id)));
    }

    /** Tries an event that failed or gave up again, now. */
    @Operation(
            summary = "Retry a Stripe event",
            description =
                    "Tries an event that failed or gave up again, now.")
    @PostMapping("/stripe/events/{id}/retry")
    @RequiresPermission(BillingPermissions.STRIPE_MANAGE)
    public EventDetailResponse retry(@PathVariable String id, @RequestHeader(value = HttpHeaders.REQUEST_ID, required = false) String requestId) {
        eventAdmin.retry(Audit.of(currentActor.get().auditName(), requestId), StripeEventId.of(id));
        return eventDetail(eventQueries.get(UUID.fromString(id)));
    }

    /** Compares recent payments and refunds with Stripe now, instead of waiting for the scheduled run. */
    @Operation(
            summary = "Reconcile payments with Stripe",
            description =
                    "Compares recent payments and refunds with what Stripe holds now, instead of waiting for the "
                    + "scheduled run. A disagreement is recorded as an incident; where Stripe is authoritative and ahead, "
                    + "the correction goes through the same path as a verified event.")
    @PostMapping("/stripe/reconcile")
    @RequiresPermission(BillingPermissions.STRIPE_MANAGE)
    public ReconcileResponse reconcile() {
        ReconcileStripeUseCase.Report report = reconcile.runOnce();
        return new ReconcileResponse(report.checked(), report.corrected(), report.mismatches());
    }

    private ConfigurationResponse configurationResponse(StripeConfiguration c) {
        String webhookUrl = c.mode() == IntegrationMode.DIRECT ? settings.directWebhookUrl(c.id()) : null;
        return new ConfigurationResponse(c.id().toString(), c.mode().name(), c.accountId(), c.environment().name(), c.status().name(), c.publishableKey(),
                c.hasWebhookSecret(), c.cardPaymentsActive(), webhookUrl, c.createdAt(), c.updatedAt(), c.connectedAt(), c.lastVerifiedAt());
    }

    private ConfigurationResponse configurationResponse(StripeConfigurationView c) {
        String webhookUrl = c.mode() == IntegrationMode.DIRECT ? settings.directWebhookUrl(new StripeConfigurationId(c.id())) : null;
        return new ConfigurationResponse(c.id().toString(), c.mode().name(), c.accountId(), c.environment().name(), c.status().name(), c.publishableKey(),
                c.webhookSecretSet(), c.cardPaymentsActive(), webhookUrl, c.createdAt(), c.updatedAt(), c.connectedAt(), c.lastVerifiedAt());
    }

    private ConnectionResponse connectionResponse(StripeConfigurationService.Connection connection) {
        return new ConnectionResponse(configurationResponse(connection.configuration()), connection.onboardingUrl());
    }

    private static EventDetailResponse eventDetail(StripeEventQueries.Detail detail) {
        return new EventDetailResponse(EventResponse.ofView(detail.event()), detail.attempts().stream().map(EventAttemptResponse::ofView).toList());
    }
}
