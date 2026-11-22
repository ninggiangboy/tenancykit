package dev.ngb.billing.customer.api.lookup;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.ngb.billing.sharedkernel.id.CustomerId;
import dev.ngb.core.error.NotFoundException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

/** The one customer-visibility rule: a Tenant System sees only the customers it mapped. */
class CustomerVisibilityTest {

    private static final CustomerId MAPPED = new CustomerId(UUID.randomUUID());
    private static final CustomerId OTHER_CUSTOMER = new CustomerId(UUID.randomUUID());
    private static final UUID MAPPING_SYSTEM = UUID.randomUUID();
    private static final UUID OTHER_SYSTEM = UUID.randomUUID();
    private static final Supplier<NotFoundException> NOT_FOUND = () -> new NotFoundException("wallet not found");

    private final CustomerLookup customers = new CustomerLookup() {
        @Override
        public Optional<CustomerSummary> find(CustomerId id) {
            return MAPPED.equals(id) ? Optional.of(new CustomerSummary(id, "Acme", true)) : Optional.empty();
        }

        @Override
        public Optional<CustomerSummary> findFor(CustomerId id, UUID tenantSystem) {
            return MAPPING_SYSTEM.equals(tenantSystem) ? find(id) : Optional.empty();
        }

        @Override
        public List<CustomerId> mappedTo(UUID tenantSystem) {
            return MAPPING_SYSTEM.equals(tenantSystem) ? List.of(MAPPED) : List.of();
        }
    };

    private final CustomerVisibility visibility = new CustomerVisibility(customers);

    @Test
    void aTenantSystemSeesTheCustomerItMapped() {
        assertThatCode(() -> visibility.requireVisible(MAPPED, MAPPING_SYSTEM, NOT_FOUND)).doesNotThrowAnyException();
    }

    @Test
    void aTenantSystemDoesNotSeeAnotherSystemCustomer() {
        assertThatThrownBy(() -> visibility.requireVisible(MAPPED, OTHER_SYSTEM, NOT_FOUND))
                .isInstanceOf(NotFoundException.class)
                .hasMessage("wallet not found");
    }

    @Test
    void aTenantSystemDoesNotSeeACustomerItDidNotMap() {
        assertThatThrownBy(() -> visibility.requireVisible(OTHER_CUSTOMER, MAPPING_SYSTEM, NOT_FOUND))
                .isInstanceOf(NotFoundException.class)
                .hasMessage("wallet not found");
    }

    @Test
    void aTenantUserSeesTheWholeTenant() {
        assertThatCode(() -> visibility.requireVisible(MAPPED, null, NOT_FOUND)).doesNotThrowAnyException();
    }
}
