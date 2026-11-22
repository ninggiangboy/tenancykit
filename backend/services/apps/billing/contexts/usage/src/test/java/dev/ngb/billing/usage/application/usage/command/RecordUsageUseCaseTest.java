package dev.ngb.billing.usage.application.usage.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.ngb.billing.customer.api.lookup.CustomerLookup;
import dev.ngb.billing.customer.api.lookup.CustomerSummary;
import dev.ngb.billing.sharedkernel.id.CustomerId;
import dev.ngb.billing.usage.application.usage.port.MetricUnits;
import dev.ngb.billing.usage.application.usage.port.UsageEventRepository;
import dev.ngb.billing.usage.domain.usage.UsageEvent;
import dev.ngb.billing.usage.domain.usage.UsageTrace;
import dev.ngb.core.error.ConflictException;
import dev.ngb.core.error.IdempotencyConflictException;
import dev.ngb.core.error.NotFoundException;
import dev.ngb.core.id.TenantId;
import dev.ngb.core.runtime.Clock;
import dev.ngb.core.runtime.IdGenerator;
import dev.ngb.core.runtime.UnitOfWork;
import dev.ngb.core.tenancy.RequestScope;
import dev.ngb.core.tenancy.TenantContext;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

/**
 * Recording usage against in-memory ports: a request is validated as a whole, a repeat of the same key and content
 * returns the stored event, and the same key with different content is a conflict.
 */
class RecordUsageUseCaseTest {

    private static final Instant NOW = Instant.parse("2026-11-11T00:00:00Z");
    private static final TenantId TENANT = new TenantId(UUID.randomUUID());
    private static final CustomerId CUSTOMER = new CustomerId(UUID.randomUUID());

    private final Map<String, UsageEvent> stored = new LinkedHashMap<>();

    private final UnitOfWork passThrough = new UnitOfWork() {
        @Override
        public <T> T transactional(Supplier<T> work) {
            return work.get();
        }
    };

    private final UsageEventRepository events = new UsageEventRepository() {
        @Override
        public boolean addIfAbsent(UsageEvent event) {
            return stored.putIfAbsent(event.idempotencyKey(), event) == null;
        }

        @Override
        public Optional<UsageEvent> findByIdempotencyKey(String idempotencyKey) {
            return Optional.ofNullable(stored.get(idempotencyKey));
        }
    };

    private final CustomerLookup customers = new CustomerLookup() {
        @Override
        public Optional<CustomerSummary> find(CustomerId id) {
            return id.equals(CUSTOMER) ? Optional.of(new CustomerSummary(id, "Acme", true)) : Optional.empty();
        }

        @Override
        public Optional<CustomerSummary> findFor(CustomerId id, UUID tenantSystem) {
            return find(id);
        }

        @Override
        public List<CustomerId> mappedTo(UUID tenantSystem) {
            return List.of(CUSTOMER);
        }
    };

    private final MetricUnits metricUnits =
            metric -> "compute.vcpu_minute".equals(metric) ? Optional.of("vCPU-minute") : Optional.empty();

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

    private final RecordUsageUseCase useCase = new RecordUsageUseCase(
            passThrough,
            events,
            customers,
            metricUnits,
            tenantContext,
            new IdGenerator() {
                @Override
                public UUID newId() {
                    return UUID.randomUUID();
                }
            },
            new Clock() {
                @Override
                public Instant now() {
                    return NOW;
                }
            });

    private static RecordUsageUseCase.Item item(String key, String quantity) {
        return new RecordUsageUseCase.Item(
                CUSTOMER, "compute.vcpu_minute", new BigDecimal(quantity), "vCPU-minute", NOW, key,
                new UsageTrace(null, "ORD-1", "src-1"));
    }

    @Test
    void aNewEventIsStoredUnderItsKeyWithTheMetricsUnit() {
        RecordUsageUseCase.Recorded recorded = useCase.record(item("usage-1", "120"));

        assertThat(recorded.replayed()).isFalse();
        assertThat(recorded.event().tenantId()).isEqualTo(TENANT);
        assertThat(recorded.event().quantity()).isEqualByComparingTo("120");
        assertThat(recorded.event().unit()).isEqualTo("vCPU-minute");
        assertThat(recorded.event().idempotencyKey()).isEqualTo("usage-1");
        assertThat(stored).hasSize(1);
    }

    @Test
    void aRepeatOfTheSameKeyAndContentReturnsTheStoredEvent() {
        RecordUsageUseCase.Recorded first = useCase.record(item("usage-1", "120"));

        RecordUsageUseCase.Recorded second = useCase.record(item("usage-1", "120"));

        assertThat(second.replayed()).isTrue();
        assertThat(second.event().id()).isEqualTo(first.event().id());
        assertThat(stored).as("one row, however often the key arrives").hasSize(1);
    }

    @Test
    void theSameKeyWithDifferentContentIsAConflict() {
        useCase.record(item("usage-1", "120"));

        assertThatThrownBy(() -> useCase.record(item("usage-1", "999")))
                .isInstanceOf(IdempotencyConflictException.class);
        assertThat(stored).hasSize(1);
    }

    @Test
    void anUnknownMetricIsRefused() {
        RecordUsageUseCase.Item unknown = new RecordUsageUseCase.Item(
                CUSTOMER, "storage.gib_month", BigDecimal.ONE, "GiB-month", NOW, "usage-2", new UsageTrace(null, null, null));

        assertThatThrownBy(() -> useCase.record(unknown))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unknown metric");
        assertThat(stored).isEmpty();
    }

    @Test
    void aUnitThatIsNotTheMetricsUnitIsRefused() {
        RecordUsageUseCase.Item wrongUnit = new RecordUsageUseCase.Item(
                CUSTOMER, "compute.vcpu_minute", BigDecimal.ONE, "GiB-month", NOW, "usage-3", new UsageTrace(null, null, null));

        assertThatThrownBy(() -> useCase.record(wrongUnit))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("measured in");
    }

    @Test
    void anUnknownCustomerIsNotFound() {
        RecordUsageUseCase.Item stranger = new RecordUsageUseCase.Item(
                new CustomerId(UUID.randomUUID()), "compute.vcpu_minute", BigDecimal.ONE, "vCPU-minute", NOW, "usage-4",
                new UsageTrace(null, null, null));

        assertThatThrownBy(() -> useCase.record(stranger)).isInstanceOf(NotFoundException.class);
    }

    @Test
    void aDeactivatedCustomerIsAConflict() {
        RecordUsageUseCase deactivating = new RecordUsageUseCase(
                passThrough,
                events,
                new CustomerLookup() {
                    @Override
                    public Optional<CustomerSummary> find(CustomerId id) {
                        return Optional.of(new CustomerSummary(id, "Acme", false));
                    }

                    @Override
                    public Optional<CustomerSummary> findFor(CustomerId id, UUID tenantSystem) {
                        return find(id);
                    }

                    @Override
                    public List<CustomerId> mappedTo(UUID tenantSystem) {
                        return List.of();
                    }
                },
                metricUnits,
                tenantContext,
                UUID::randomUUID,
                () -> NOW);

        assertThatThrownBy(() -> deactivating.record(item("usage-5", "1"))).isInstanceOf(ConflictException.class);
    }

    @Test
    void anEmptyBatchIsRefused() {
        assertThatThrownBy(() -> useCase.record(List.of())).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aBatchThatFailsValidationStoresNothing() {
        assertThatThrownBy(() -> useCase.record(List.of(item("usage-6", "1"), item("usage-7", "0"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("usage event 1");

        assertThat(stored).as("a batch is all-or-nothing").isEmpty();
    }

    @Test
    void aBatchReturnsOneResultPerItemInOrder() {
        List<RecordUsageUseCase.Recorded> results =
                useCase.record(List.of(item("usage-8", "1"), item("usage-9", "2")));

        assertThat(results).hasSize(2);
        assertThat(results.get(0).event().quantity()).isEqualByComparingTo("1");
        assertThat(results.get(1).event().quantity()).isEqualByComparingTo("2");
        assertThat(stored).hasSize(2);
    }

    @Test
    void anEventFurtherAheadThanTheClockSkewIsRefused() {
        RecordUsageUseCase.Item future = new RecordUsageUseCase.Item(
                CUSTOMER, "compute.vcpu_minute", BigDecimal.ONE, "vCPU-minute", NOW.plusSeconds(3600), "usage-10",
                new UsageTrace(null, null, null));

        assertThatThrownBy(() -> useCase.record(future))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("in the future");
    }

    @Test
    void anIdempotencyKeyIsRequired() {
        assertThatThrownBy(() -> useCase.record(item(" ", "1")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Idempotency-Key");
    }
}
