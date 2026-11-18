package dev.ngb.core.spring.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.ngb.core.id.TenantId;
import dev.ngb.core.runtime.TransactionScopeHook;
import dev.ngb.core.tenancy.RequestScope;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

/**
 * The transaction seam: a new transaction runs the hook for the current scope before the work, a nested unit of work
 * joins it only in the same scope, and the scope it bound is released whether the work commits or rolls back.
 */
class SpringUnitOfWorkTest {

    private static final RequestScope TENANT = RequestScope.tenant(new TenantId(UUID.randomUUID()));

    private final FakeTransactions transactions = new FakeTransactions();
    private final List<RequestScope> hooked = new ArrayList<>();

    private final TransactionScopeHook hook = new TransactionScopeHook() {
        @Override
        public RequestScope currentScope() {
            return TENANT;
        }

        @Override
        public void onNewTransaction(RequestScope scope) {
            hooked.add(scope);
        }
    };

    @Test
    void aNewTransactionRunsTheHookThenTheWorkThenCommits() {
        SpringUnitOfWork unitOfWork = new SpringUnitOfWork(transactions, hook);
        List<String> order = new ArrayList<>();

        String result = unitOfWork.transactional(() -> {
            order.add("work");
            return "done";
        });

        assertThat(result).isEqualTo("done");
        assertThat(hooked).containsExactly(TENANT);
        assertThat(order).containsExactly("work");
        assertThat(transactions.events).containsExactly("begin", "commit");
    }

    @Test
    void aNestedUnitOfWorkInTheSameScopeJoinsAndRunsTheHookOnce() {
        SpringUnitOfWork unitOfWork = new SpringUnitOfWork(transactions, hook);

        String result = unitOfWork.transactional(() -> unitOfWork.transactional(() -> "inner"));

        assertThat(result).isEqualTo("inner");
        assertThat(hooked).as("the hook belongs to the outer transaction").containsExactly(TENANT);
        assertThat(transactions.events).containsExactly("begin", "begin", "commit", "commit");
    }

    @Test
    void aNestedUnitOfWorkInAnotherScopeIsRefused() {
        AtomicReference<RequestScope> current = new AtomicReference<>(TENANT);
        RequestScope other = RequestScope.tenant(new TenantId(UUID.randomUUID()));
        SpringUnitOfWork unitOfWork = new SpringUnitOfWork(transactions, new TransactionScopeHook() {
            @Override
            public RequestScope currentScope() {
                return current.get();
            }

            @Override
            public void onNewTransaction(RequestScope scope) {}
        });

        assertThatThrownBy(() -> unitOfWork.transactional(() -> {
                    current.set(other);
                    return unitOfWork.transactional(() -> "inner");
                }))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("same scope");
        assertThat(transactions.events).containsExactly("begin", "begin", "rollback", "rollback");
    }

    @Test
    void aFailureRollsBackAndReleasesTheScope() {
        SpringUnitOfWork unitOfWork = new SpringUnitOfWork(transactions, hook);

        assertThatThrownBy(() -> unitOfWork.transactional(() -> {
                    throw new IllegalStateException("boom");
                }))
                .isInstanceOf(IllegalStateException.class);

        assertThat(transactions.events).containsExactly("begin", "rollback");
        assertThat(unitOfWork.transactional(() -> "ok")).isEqualTo("ok");
        assertThat(hooked).as("a released scope is rebuilt for the next transaction").hasSize(2);
    }

    private static final class FakeTransactions implements PlatformTransactionManager {

        private final List<String> events = new ArrayList<>();

        @Override
        public TransactionStatus getTransaction(TransactionDefinition definition) {
            events.add("begin");
            return new SimpleTransactionStatus();
        }

        @Override
        public void commit(TransactionStatus status) {
            events.add("commit");
        }

        @Override
        public void rollback(TransactionStatus status) {
            events.add("rollback");
        }
    }
}
