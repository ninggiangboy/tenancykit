package dev.ngb.crm.boot.config;

import dev.ngb.core.runtime.Clock;
import dev.ngb.core.runtime.IdGenerator;
import dev.ngb.core.runtime.UnitOfWork;
import dev.ngb.core.tenancy.TenantContext;
import dev.ngb.crm.activity.adapter.activity.json.ActivityPayloadJson;
import dev.ngb.crm.activity.adapter.activity.persistence.JooqActivityErasure;
import dev.ngb.crm.activity.adapter.activity.persistence.JooqActivityReadRepository;
import dev.ngb.crm.activity.adapter.activity.persistence.JooqActivityRepository;
import dev.ngb.crm.activity.adapter.activity.persistence.JooqActivityTimeline;
import dev.ngb.crm.activity.api.activity.ActivityTimeline;
import dev.ngb.crm.activity.application.activity.command.RecordActivitiesBatchUseCase;
import dev.ngb.crm.activity.application.activity.command.RecordActivityUseCase;
import dev.ngb.crm.activity.application.activity.service.ActivityPayloads;
import dev.ngb.crm.activity.application.activity.port.ActivityRepository;
import dev.ngb.crm.activity.application.activity.query.ActivityQueries;
import dev.ngb.crm.activity.application.activity.query.ActivityReadRepository;
import dev.ngb.crm.customers.api.customer.CustomerDirectory;
import dev.ngb.crm.customers.api.customer.CustomerErasureParticipant;
import org.jooq.DSLContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the activity context: how a payload crosses into JSON, the two repositories, the timeline another context
 * reads, the single appending and the batch.
 *
 * <p>The payload bound and the future skew are deployment settings rather than per-Tenant ones, because they bound one
 * request and one row; they are read here and passed in, so the application layer never reads a property itself.
 *
 * <p>The customer seam is the customers context's api: an activity names a customer that must already exist, the
 * resolution goes through the same lookup every other context uses, and the rows this context owns are cleared here
 * when that customer is erased.
 */
@Configuration(proxyBeanMethods = false)
class ActivityConfiguration {

    /** The codec the write path, the read path and the replay comparison all share, so they agree on the bytes. */
    @Bean
    ActivityPayloads activityPayloads() {
        return new ActivityPayloadJson();
    }

    @Bean
    ActivityRepository activityRepository(DSLContext dsl, ActivityPayloads payloads) {
        return new JooqActivityRepository(dsl, payloads);
    }

    @Bean
    ActivityReadRepository activityReadRepository(DSLContext dsl, ActivityPayloads payloads) {
        return new JooqActivityReadRepository(dsl, payloads);
    }

    /** What this context clears when a customer's personal data is erased: the payloads, the sources and the refs. */
    @Bean
    CustomerErasureParticipant activityErasure(DSLContext dsl) {
        return new JooqActivityErasure(dsl);
    }

    @Bean
    ActivityTimeline activityTimeline(ActivityReadRepository activities) {
        return new JooqActivityTimeline(activities);
    }

    @Bean
    ActivityQueries activityQueries(
            UnitOfWork unitOfWork, ActivityReadRepository activities, CustomerDirectory customers) {
        return new ActivityQueries(unitOfWork, activities, customers);
    }

    @Bean
    RecordActivityUseCase recordActivityUseCase(
            UnitOfWork unitOfWork,
            ActivityRepository activities,
            CustomerDirectory customers,
            ActivityPayloads payloads,
            TenantContext tenantContext,
            IdGenerator ids,
            Clock clock,
            CrmSettings settings) {
        return new RecordActivityUseCase(
                unitOfWork,
                activities,
                customers,
                payloads,
                tenantContext,
                ids,
                clock,
                settings.activity().maxPayloadBytes(),
                settings.orders().maxFutureSkew());
    }

    @Bean
    RecordActivitiesBatchUseCase recordActivitiesBatchUseCase(
            UnitOfWork unitOfWork, RecordActivityUseCase record, CrmSettings settings) {
        return new RecordActivitiesBatchUseCase(
                unitOfWork, record, settings.batches().maxActivities());
    }
}
