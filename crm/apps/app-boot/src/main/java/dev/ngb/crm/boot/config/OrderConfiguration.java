package dev.ngb.crm.boot.config;

import dev.ngb.core.event.EventPublisher;
import dev.ngb.core.runtime.Clock;
import dev.ngb.core.runtime.IdGenerator;
import dev.ngb.core.runtime.UnitOfWork;
import dev.ngb.core.tenancy.TenantContext;
import dev.ngb.crm.customers.api.customer.CustomerDirectory;
import dev.ngb.crm.orders.adapter.order.persistence.JooqOrderReadRepository;
import dev.ngb.crm.orders.adapter.order.persistence.JooqOrderRepository;
import dev.ngb.crm.orders.application.order.command.RecordOrderUseCase;
import dev.ngb.crm.orders.application.order.command.RecordOrdersBatchUseCase;
import dev.ngb.crm.orders.application.order.port.OrderRepository;
import dev.ngb.crm.orders.application.order.query.OrderQueries;
import dev.ngb.crm.orders.application.order.query.OrderReadRepository;
import dev.ngb.crm.orders.application.order.service.OrderEventPublisher;
import dev.ngb.crm.orders.application.returns.command.RecordOrderReturnUseCase;
import org.jooq.DSLContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the orders context: its two repositories, the two reads, the recording of an order and of a return, and the
 * batch.
 *
 * <p>The order caps and the future skew are deployment settings rather than per-Tenant ones, because they bound one
 * request and protect this service's own outbox and webhook delivery; they are read here and passed in, so the
 * application layer never reads a property itself.
 *
 * <p>The customer seam is the customers context's api, not its repositories: an order resolves and creates a customer
 * through the same upsert every other path uses, so a first order's customer is announced exactly like one created by
 * {@code PUT /customers/{externalRef}}.
 */
@Configuration(proxyBeanMethods = false)
class OrderConfiguration {

    @Bean
    OrderRepository orderRepository(DSLContext dsl) {
        return new JooqOrderRepository(dsl);
    }

    @Bean
    OrderReadRepository orderReadRepository(DSLContext dsl, CustomerDirectory customers) {
        return new JooqOrderReadRepository(dsl, customers);
    }

    @Bean
    OrderQueries orderQueries(UnitOfWork unitOfWork, OrderReadRepository orders, CustomerDirectory customers) {
        return new OrderQueries(unitOfWork, orders, customers);
    }

    @Bean
    OrderEventPublisher orderEventPublisher(
            EventPublisher events, IdGenerator ids, CustomerDirectory customers, Clock clock) {
        return new OrderEventPublisher(events, ids, customers, clock);
    }

    @Bean
    RecordOrderUseCase recordOrderUseCase(
            UnitOfWork unitOfWork,
            OrderRepository orders,
            CustomerDirectory customers,
            OrderEventPublisher events,
            TenantContext tenantContext,
            IdGenerator ids,
            Clock clock,
            CrmSettings settings) {
        return new RecordOrderUseCase(
                unitOfWork,
                orders,
                customers,
                events,
                tenantContext,
                ids,
                clock,
                settings.orders().maxLinesPerOrder(),
                settings.orders().maxFutureSkew());
    }

    @Bean
    RecordOrderReturnUseCase recordOrderReturnUseCase(
            UnitOfWork unitOfWork,
            OrderRepository orders,
            OrderEventPublisher events,
            IdGenerator ids,
            Clock clock,
            CrmSettings settings) {
        return new RecordOrderReturnUseCase(
                unitOfWork, orders, events, ids, clock, settings.orders().maxFutureSkew());
    }

    @Bean
    RecordOrdersBatchUseCase recordOrdersBatchUseCase(
            UnitOfWork unitOfWork, RecordOrderUseCase record, CrmSettings settings) {
        return new RecordOrdersBatchUseCase(
                unitOfWork,
                record,
                settings.batches().maxOrders(),
                settings.batches().maxOrderLines());
    }
}
