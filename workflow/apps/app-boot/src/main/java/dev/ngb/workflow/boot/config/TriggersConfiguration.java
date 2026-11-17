package dev.ngb.workflow.boot.config;

import dev.ngb.core.runtime.Clock;
import dev.ngb.core.runtime.IdGenerator;
import dev.ngb.core.runtime.UnitOfWork;
import dev.ngb.core.tenancy.TenantContext;
import dev.ngb.workflow.definitions.api.expression.ExpressionEvaluator;
import dev.ngb.workflow.definitions.api.version.VersionReader;
import dev.ngb.workflow.runtime.api.instance.InstanceStarter;
import dev.ngb.workflow.shared.secret.SecretBox;
import dev.ngb.workflow.triggers.adapter.trigger.cron.SpringCronCalendar;
import dev.ngb.workflow.triggers.adapter.trigger.crypto.AesSecretCipher;
import dev.ngb.workflow.triggers.adapter.trigger.persistence.JooqInboundEventRepository;
import dev.ngb.workflow.triggers.adapter.trigger.persistence.JooqScheduleRepository;
import dev.ngb.workflow.triggers.adapter.trigger.persistence.JooqTriggerReadRepository;
import dev.ngb.workflow.triggers.adapter.trigger.persistence.JooqTriggerRepository;
import dev.ngb.workflow.triggers.application.trigger.command.FireScheduleUseCase;
import dev.ngb.workflow.triggers.application.trigger.command.ManageTriggerUseCase;
import dev.ngb.workflow.triggers.application.trigger.command.ReceiveEventUseCase;
import dev.ngb.workflow.triggers.application.trigger.command.StartManualUseCase;
import dev.ngb.workflow.triggers.application.trigger.port.CronCalendar;
import dev.ngb.workflow.triggers.application.trigger.port.InboundEventRepository;
import dev.ngb.workflow.triggers.application.trigger.port.ScheduleRepository;
import dev.ngb.workflow.triggers.application.trigger.port.SecretCipher;
import dev.ngb.workflow.triggers.application.trigger.port.TriggerRepository;
import dev.ngb.workflow.triggers.application.trigger.query.TriggerQueries;
import dev.ngb.workflow.triggers.application.trigger.query.TriggerReadRepository;
import dev.ngb.workflow.triggers.application.trigger.service.EventFilters;
import dev.ngb.workflow.triggers.application.trigger.service.TriggerRules;
import java.util.Base64;
import org.jooq.DSLContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.json.JsonMapper;

/**
 * Wires the triggers context: its four stores, the signing cipher, the cron arithmetic, the verifier and the three ways
 * work starts (a hook, a manual call and a schedule).
 */
@Configuration(proxyBeanMethods = false)
class TriggersConfiguration {

    @Bean
    TriggerRepository triggerRepository(DSLContext dsl, JsonMapper json) {
        return new JooqTriggerRepository(dsl, json);
    }

    @Bean
    TriggerReadRepository triggerReadRepository(DSLContext dsl, JsonMapper json) {
        return new JooqTriggerReadRepository(dsl, json);
    }

    @Bean
    InboundEventRepository inboundEventRepository(DSLContext dsl, JsonMapper json) {
        return new JooqInboundEventRepository(dsl, json);
    }

    @Bean
    ScheduleRepository scheduleRepository(DSLContext dsl) {
        return new JooqScheduleRepository(dsl);
    }

    @Bean
    SecretCipher secretCipher(WorkflowSettings settings) {
        String key = settings.secrets().masterKey();
        if (key == null || key.isBlank()) {
            throw new IllegalStateException(
                    "workflow.secrets.master-key is required: a base64 32-byte AES-256 key");
        }
        return new AesSecretCipher(new SecretBox(Base64.getDecoder().decode(key), settings.secrets().keyId()));
    }

    @Bean
    CronCalendar cronCalendar() {
        return new SpringCronCalendar();
    }

    @Bean
    TriggerRules triggerRules() {
        return new TriggerRules();
    }

    @Bean
    EventFilters eventFilters(ExpressionEvaluator expressions) {
        return new EventFilters(expressions);
    }

    @Bean
    TriggerQueries triggerQueries(UnitOfWork unitOfWork, TriggerReadRepository triggers) {
        return new TriggerQueries(unitOfWork, triggers);
    }

    @Bean
    ManageTriggerUseCase manageTriggerUseCase(
            UnitOfWork unitOfWork,
            TriggerRepository triggers,
            ScheduleRepository schedules,
            VersionReader versions,
            SecretCipher cipher,
            CronCalendar calendar,
            TriggerRules rules,
            TenantContext tenantContext,
            IdGenerator ids,
            Clock clock) {
        return new ManageTriggerUseCase(
                unitOfWork, triggers, schedules, versions, cipher, calendar, rules, tenantContext, ids, clock);
    }

    @Bean
    ReceiveEventUseCase receiveEventUseCase(
            UnitOfWork unitOfWork,
            TriggerRepository triggers,
            InboundEventRepository events,
            VersionReader versions,
            InstanceStarter starter,
            SecretCipher cipher,
            EventFilters filters,
            TenantContext tenantContext,
            IdGenerator ids,
            Clock clock) {
        return new ReceiveEventUseCase(
                unitOfWork, triggers, events, versions, starter, cipher, filters, tenantContext, ids, clock);
    }

    @Bean
    StartManualUseCase startManualUseCase(UnitOfWork unitOfWork, VersionReader versions, InstanceStarter starter) {
        return new StartManualUseCase(unitOfWork, versions, starter);
    }

    @Bean
    FireScheduleUseCase fireScheduleUseCase(
            UnitOfWork unitOfWork,
            ScheduleRepository schedules,
            TriggerRepository triggers,
            VersionReader versions,
            InstanceStarter starter,
            CronCalendar calendar) {
        return new FireScheduleUseCase(unitOfWork, schedules, triggers, versions, starter, calendar);
    }
}
