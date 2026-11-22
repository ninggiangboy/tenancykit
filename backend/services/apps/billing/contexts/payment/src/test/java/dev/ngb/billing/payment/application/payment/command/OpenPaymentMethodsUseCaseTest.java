package dev.ngb.billing.payment.application.payment.command;

import static org.assertj.core.api.Assertions.assertThat;

import dev.ngb.billing.customer.api.lookup.CustomerLookup;
import dev.ngb.billing.customer.api.lookup.CustomerSummary;
import dev.ngb.billing.payment.application.payment.port.PaymentProvider;
import dev.ngb.billing.payment.application.payment.port.PaymentProvider.Access;
import dev.ngb.billing.payment.application.payment.port.PaymentProvider.AccountState;
import dev.ngb.billing.payment.application.payment.port.PaymentProvider.Checkout;
import dev.ngb.billing.payment.application.payment.port.PaymentProvider.CheckoutRequest;
import dev.ngb.billing.payment.application.payment.port.PaymentProvider.CreatedCheckout;
import dev.ngb.billing.payment.application.payment.port.PaymentProvider.Intent;
import dev.ngb.billing.payment.application.payment.port.PaymentProvider.ProviderEvent;
import dev.ngb.billing.payment.application.payment.port.PaymentProvider.RefundObject;
import dev.ngb.billing.payment.application.payment.port.PaymentProvider.RefundRequest;
import dev.ngb.billing.payment.application.payment.port.PaymentProvider.VerifiedAccount;
import dev.ngb.billing.payment.application.payment.port.StripeCustomerRepository;
import dev.ngb.billing.payment.application.payment.service.PaymentSettings;
import dev.ngb.billing.payment.application.stripe.port.StripeConfigurationRepository;
import dev.ngb.billing.payment.application.stripe.service.ProviderAccess;
import dev.ngb.billing.payment.domain.stripe.StripeConfiguration;
import dev.ngb.billing.payment.domain.stripe.StripeConfigurationId;
import dev.ngb.billing.payment.domain.stripe.StripeEnvironment;
import dev.ngb.billing.sharedkernel.id.CustomerId;
import dev.ngb.core.id.TenantId;
import dev.ngb.core.runtime.UnitOfWork;
import dev.ngb.core.security.SecretCipher;
import dev.ngb.core.tenancy.RequestScope;
import dev.ngb.core.tenancy.TenantContext;
import dev.ngb.core.util.Backoff;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

/**
 * The provider customer of a Customer on one Stripe configuration, against in-memory ports. The repository answers
 * like {@code stripe_customers} does under row level security: a row is visible only while a unit of work has the
 * tenant bound, so a read taken outside the transaction sees nothing.
 */
class OpenPaymentMethodsUseCaseTest {

    private static final TenantId TENANT = new TenantId(UUID.randomUUID());
    private static final Instant NOW = Instant.parse("2026-11-10T10:00:00Z");

    private final CustomerId customerId = new CustomerId(UUID.randomUUID());
    private final StripeConfiguration configuration = StripeConfiguration.direct(new StripeConfigurationId(UUID.randomUUID()), TENANT, "acct_1",
            StripeEnvironment.TEST, "cipher-key", "cipher-whsec", "pk_test_1", NOW);

    private final ScopedUnitOfWork unitOfWork = new ScopedUnitOfWork();
    private final Map<String, String> rows = new LinkedHashMap<>();
    private final List<String> createdCustomers = new ArrayList<>();
    private final List<String> portalCustomers = new ArrayList<>();

    /** A row another request commits between this call's read and its insert, as a race would leave it. */
    private String wonByAnotherRequest;

    /** A unit of work that binds the tenant for the length of the call, as the runtime's scope hook does. */
    private static final class ScopedUnitOfWork implements UnitOfWork {

        private int depth;

        @Override
        public <T> T transactional(Supplier<T> work) {
            depth++;
            try {
                return work.get();
            } finally {
                depth--;
            }
        }

        boolean bound() {
            return depth > 0;
        }
    }

    private final StripeConfigurationRepository configurations = new StripeConfigurationRepository() {
        @Override
        public void add(StripeConfiguration added) {}

        @Override
        public void update(StripeConfiguration updated) {}

        @Override
        public Optional<StripeConfiguration> findById(StripeConfigurationId id) {
            return Optional.of(configuration);
        }

        @Override
        public Optional<StripeConfiguration> findLive() {
            return Optional.of(configuration);
        }
    };

    private final StripeCustomerRepository stripeCustomers = new StripeCustomerRepository() {
        @Override
        public Optional<String> find(CustomerId customer, StripeConfigurationId config) {
            return unitOfWork.bound() ? Optional.ofNullable(rows.get(key(customer, config))) : Optional.empty();
        }

        @Override
        public void addIfAbsent(TenantId tenant, CustomerId customer, StripeConfigurationId config, String stripeCustomerId, Instant now) {
            if (wonByAnotherRequest != null) {
                rows.put(key(customer, config), wonByAnotherRequest);
                return;
            }
            rows.putIfAbsent(key(customer, config), stripeCustomerId);
        }
    };

    private final CustomerLookup customers = new CustomerLookup() {
        @Override
        public Optional<CustomerSummary> find(CustomerId id) {
            return Optional.of(new CustomerSummary(id, "Acme", true));
        }

        @Override
        public Optional<CustomerSummary> findFor(CustomerId id, UUID tenantSystem) {
            return find(id);
        }

        @Override
        public List<CustomerId> mappedTo(UUID tenantSystem) {
            return List.of();
        }
    };

    private final PaymentProvider provider = new PaymentProvider() {
        @Override
        public String createCustomer(Access access, String reference, String name, String email, String idempotencyKey) {
            String created = "cus_created_" + (createdCustomers.size() + 1);
            createdCustomers.add(created);
            return created;
        }

        @Override
        public String createBillingPortalSession(Access access, String customerId, String returnUrl) {
            portalCustomers.add(customerId);
            return "https://billing.stripe.test/session/" + portalCustomers.size();
        }

        @Override
        public VerifiedAccount verifyKey(String secretKey) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ProviderEvent verifyWebhook(String payload, String signatureHeader, String webhookSecret) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ProviderEvent interpret(String payload) {
            throw new UnsupportedOperationException();
        }

        @Override
        public String createConnectedAccount(String platformSecretKey, String displayName, String contactEmail, String idempotencyKey) {
            throw new UnsupportedOperationException();
        }

        @Override
        public String createOnboardingLink(String platformSecretKey, String accountId, String returnUrl, String refreshUrl) {
            throw new UnsupportedOperationException();
        }

        @Override
        public AccountState retrieveAccount(String platformSecretKey, String accountId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public CreatedCheckout createCheckout(Access access, CheckoutRequest request, String idempotencyKey) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Intent retrievePayment(Access access, String paymentIntentId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public RefundObject createRefund(Access access, RefundRequest request, String idempotencyKey) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<Checkout> recentCheckouts(Access access, Instant since) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<RefundObject> recentRefunds(Access access, Instant since) {
            throw new UnsupportedOperationException();
        }
    };

    private final SecretCipher cipher = new SecretCipher() {
        @Override
        public String encrypt(String plaintext, String context) {
            return plaintext;
        }

        @Override
        public String decrypt(String ciphertext, String context) {
            return ciphertext;
        }
    };

    private final TenantContext tenantContext = new TenantContext() {
        @Override
        public RequestScope current() {
            return RequestScope.tenant(TENANT);
        }

        @Override
        public <T> T callAs(RequestScope scope, Supplier<T> work) {
            return work.get();
        }
    };

    private final OpenPaymentMethodsUseCase useCase = new OpenPaymentMethodsUseCase(unitOfWork, configurations, stripeCustomers, customers, provider,
            new ProviderAccess(cipher, settings()), tenantContext, () -> NOW);

    private static PaymentSettings settings() {
        return new PaymentSettings("https://billing.test", null, "https://acme.example/paid", "https://acme.example/cancelled", Duration.ofDays(7), 8,
                new Backoff(Duration.ofSeconds(30), Duration.ofHours(6), 0.2), 25, Duration.ofDays(3));
    }

    private static String key(CustomerId customer, StripeConfigurationId config) {
        return customer + "/" + config;
    }

    private OpenPaymentMethodsUseCase.Result open() {
        return useCase.execute(new OpenPaymentMethodsUseCase.Command(customerId, "https://acme.example/account"));
    }

    @Test
    void theProviderCustomerOnRecordIsReusedOnASecondCall() {
        assertThat(open().url()).isNotBlank();
        assertThat(open().url()).isNotBlank();

        assertThat(createdCustomers).as("the provider customer is created once").hasSize(1);
        assertThat(portalCustomers).as("both calls open the portal of the customer Billing recorded").containsExactly("cus_created_1", "cus_created_1");
    }

    @Test
    void theRowThatStandsInTheDatabaseWinsOverTheCustomerThisCallCreated() {
        // The insert loses to a request that committed the row a moment earlier. Reading the row outside the unit of
        // work would see nothing under row level security and hand the losing id to the provider.
        wonByAnotherRequest = "cus_winner";

        assertThat(open().url()).isNotBlank();

        assertThat(createdCustomers).hasSize(1);
        assertThat(portalCustomers).as("the portal session names the customer the tenant actually has").containsExactly("cus_winner");
    }
}
