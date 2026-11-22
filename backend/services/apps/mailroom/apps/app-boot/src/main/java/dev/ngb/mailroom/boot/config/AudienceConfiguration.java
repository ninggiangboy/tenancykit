package dev.ngb.mailroom.boot.config;

import dev.ngb.core.runtime.Clock;
import dev.ngb.core.runtime.IdGenerator;
import dev.ngb.core.runtime.UnitOfWork;
import dev.ngb.core.tenancy.TenantContext;
import dev.ngb.mailroom.audience.adapter.audience.persistence.JooqAudienceReadRepository;
import dev.ngb.mailroom.audience.adapter.group.persistence.JooqGroupRepository;
import dev.ngb.mailroom.audience.adapter.group.persistence.JooqMembershipRepository;
import dev.ngb.mailroom.audience.adapter.receiver.persistence.JooqReceiverRepository;
import dev.ngb.mailroom.audience.adapter.segment.persistence.JooqSegmentMatcher;
import dev.ngb.mailroom.audience.adapter.segment.persistence.JooqSegmentReadRepository;
import dev.ngb.mailroom.audience.adapter.segment.persistence.JooqSegmentRepository;
import dev.ngb.mailroom.audience.adapter.suppression.persistence.JooqSuppressionRepository;
import dev.ngb.mailroom.audience.api.audience.AudienceDirectory;
import dev.ngb.mailroom.audience.api.eligibility.SendEligibility;
import dev.ngb.mailroom.audience.api.unsubscribe.UnsubscribeAddress;
import dev.ngb.mailroom.audience.application.audience.query.AudienceQueries;
import dev.ngb.mailroom.audience.application.audience.query.AudienceReadRepository;
import dev.ngb.mailroom.audience.application.audience.service.AudienceDirectoryService;
import dev.ngb.mailroom.audience.application.eligibility.service.SendEligibilityService;
import dev.ngb.mailroom.audience.application.group.command.CreateGroupUseCase;
import dev.ngb.mailroom.audience.application.group.command.DeleteGroupUseCase;
import dev.ngb.mailroom.audience.application.group.command.ManageMembersUseCase;
import dev.ngb.mailroom.audience.application.group.command.UpdateGroupUseCase;
import dev.ngb.mailroom.audience.application.group.port.GroupRepository;
import dev.ngb.mailroom.audience.application.group.port.MembershipRepository;
import dev.ngb.mailroom.audience.application.receiver.command.BulkUpsertReceiversUseCase;
import dev.ngb.mailroom.audience.application.receiver.command.DeleteReceiverUseCase;
import dev.ngb.mailroom.audience.application.receiver.command.UpdateReceiverUseCase;
import dev.ngb.mailroom.audience.application.receiver.command.UpsertReceiverUseCase;
import dev.ngb.mailroom.audience.application.receiver.port.ReceiverRepository;
import dev.ngb.mailroom.audience.application.segment.command.CreateSegmentUseCase;
import dev.ngb.mailroom.audience.application.segment.command.DeleteSegmentUseCase;
import dev.ngb.mailroom.audience.application.segment.command.PreviewSegmentUseCase;
import dev.ngb.mailroom.audience.application.segment.command.UpdateSegmentUseCase;
import dev.ngb.mailroom.audience.application.segment.port.SegmentMatcher;
import dev.ngb.mailroom.audience.application.segment.port.SegmentRepository;
import dev.ngb.mailroom.audience.application.segment.query.SegmentQueries;
import dev.ngb.mailroom.audience.application.segment.query.SegmentReadRepository;
import dev.ngb.mailroom.audience.application.suppression.command.AddSuppressionUseCase;
import dev.ngb.mailroom.audience.application.suppression.command.DeleteSuppressionUseCase;
import dev.ngb.mailroom.audience.application.suppression.port.SuppressionRepository;
import dev.ngb.mailroom.audience.application.unsubscribe.service.UnsubscribeAddressService;
import org.jooq.DSLContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.json.JsonMapper;

/** Wires the audience context: receivers, groups, suppressions, segments and the two seams other contexts use. */
@Configuration(proxyBeanMethods = false)
class AudienceConfiguration {

    @Bean
    ReceiverRepository receiverRepository(DSLContext dsl, JsonMapper json) {
        return new JooqReceiverRepository(dsl, json);
    }

    @Bean
    GroupRepository groupRepository(DSLContext dsl) {
        return new JooqGroupRepository(dsl);
    }

    @Bean
    MembershipRepository membershipRepository(DSLContext dsl) {
        return new JooqMembershipRepository(dsl);
    }

    @Bean
    SuppressionRepository suppressionRepository(DSLContext dsl) {
        return new JooqSuppressionRepository(dsl);
    }

    @Bean
    AudienceReadRepository audienceReadRepository(DSLContext dsl, JsonMapper json) {
        return new JooqAudienceReadRepository(dsl, json);
    }

    @Bean
    AudienceQueries audienceQueries(UnitOfWork unitOfWork, AudienceReadRepository audience) {
        return new AudienceQueries(unitOfWork, audience);
    }

    @Bean
    SegmentRepository segmentRepository(DSLContext dsl, JsonMapper json) {
        return new JooqSegmentRepository(dsl, json);
    }

    @Bean
    SegmentReadRepository segmentReadRepository(DSLContext dsl, JsonMapper json) {
        return new JooqSegmentReadRepository(dsl, json);
    }

    @Bean
    SegmentMatcher segmentMatcher(DSLContext dsl, JsonMapper json) {
        return new JooqSegmentMatcher(dsl, json);
    }

    @Bean
    SegmentQueries segmentQueries(UnitOfWork unitOfWork, SegmentReadRepository segments) {
        return new SegmentQueries(unitOfWork, segments);
    }

    @Bean
    SendEligibility sendEligibility(
            UnitOfWork unitOfWork, ReceiverRepository receivers, SuppressionRepository suppressions) {
        return new SendEligibilityService(unitOfWork, receivers, suppressions);
    }

    @Bean
    AudienceDirectory audienceDirectory(
            UnitOfWork unitOfWork,
            AudienceReadRepository audience,
            SegmentRepository segments,
            SegmentMatcher matcher) {
        return new AudienceDirectoryService(unitOfWork, audience, segments, matcher);
    }

    @Bean
    UnsubscribeAddress unsubscribeAddress(
            UnitOfWork unitOfWork,
            ReceiverRepository receivers,
            SuppressionRepository suppressions,
            Clock clock) {
        return new UnsubscribeAddressService(unitOfWork, receivers, suppressions, clock);
    }

    @Bean
    UpsertReceiverUseCase upsertReceiverUseCase(
            UnitOfWork unitOfWork,
            TenantContext tenantContext,
            ReceiverRepository receivers,
            IdGenerator ids,
            Clock clock) {
        return new UpsertReceiverUseCase(unitOfWork, tenantContext, receivers, ids, clock);
    }

    @Bean
    UpdateReceiverUseCase updateReceiverUseCase(
            UnitOfWork unitOfWork, ReceiverRepository receivers, Clock clock) {
        return new UpdateReceiverUseCase(unitOfWork, receivers, clock);
    }

    @Bean
    DeleteReceiverUseCase deleteReceiverUseCase(UnitOfWork unitOfWork, ReceiverRepository receivers) {
        return new DeleteReceiverUseCase(unitOfWork, receivers);
    }

    @Bean
    BulkUpsertReceiversUseCase bulkUpsertReceiversUseCase(
            UnitOfWork unitOfWork,
            TenantContext tenantContext,
            ReceiverRepository receivers,
            IdGenerator ids,
            Clock clock) {
        return new BulkUpsertReceiversUseCase(unitOfWork, tenantContext, receivers, ids, clock);
    }

    @Bean
    CreateGroupUseCase createGroupUseCase(
            UnitOfWork unitOfWork,
            TenantContext tenantContext,
            GroupRepository groups,
            IdGenerator ids,
            Clock clock) {
        return new CreateGroupUseCase(unitOfWork, tenantContext, groups, ids, clock);
    }

    @Bean
    UpdateGroupUseCase updateGroupUseCase(UnitOfWork unitOfWork, GroupRepository groups, Clock clock) {
        return new UpdateGroupUseCase(unitOfWork, groups, clock);
    }

    @Bean
    DeleteGroupUseCase deleteGroupUseCase(UnitOfWork unitOfWork, GroupRepository groups) {
        return new DeleteGroupUseCase(unitOfWork, groups);
    }

    @Bean
    ManageMembersUseCase manageMembersUseCase(
            UnitOfWork unitOfWork,
            TenantContext tenantContext,
            GroupRepository groups,
            ReceiverRepository receivers,
            MembershipRepository members,
            Clock clock) {
        return new ManageMembersUseCase(unitOfWork, tenantContext, groups, receivers, members, clock);
    }

    @Bean
    AddSuppressionUseCase addSuppressionUseCase(
            UnitOfWork unitOfWork,
            TenantContext tenantContext,
            SuppressionRepository suppressions,
            Clock clock) {
        return new AddSuppressionUseCase(unitOfWork, tenantContext, suppressions, clock);
    }

    @Bean
    DeleteSuppressionUseCase deleteSuppressionUseCase(
            UnitOfWork unitOfWork,
            SuppressionRepository suppressions,
            ReceiverRepository receivers,
            Clock clock) {
        return new DeleteSuppressionUseCase(unitOfWork, suppressions, receivers, clock);
    }

    @Bean
    CreateSegmentUseCase createSegmentUseCase(
            UnitOfWork unitOfWork,
            TenantContext tenantContext,
            SegmentRepository segments,
            IdGenerator ids,
            Clock clock) {
        return new CreateSegmentUseCase(unitOfWork, tenantContext, segments, ids, clock);
    }

    @Bean
    UpdateSegmentUseCase updateSegmentUseCase(
            UnitOfWork unitOfWork, SegmentRepository segments, Clock clock) {
        return new UpdateSegmentUseCase(unitOfWork, segments, clock);
    }

    @Bean
    DeleteSegmentUseCase deleteSegmentUseCase(UnitOfWork unitOfWork, SegmentRepository segments) {
        return new DeleteSegmentUseCase(unitOfWork, segments);
    }

    @Bean
    PreviewSegmentUseCase previewSegmentUseCase(
            UnitOfWork unitOfWork, SegmentRepository segments, SegmentMatcher matcher) {
        return new PreviewSegmentUseCase(unitOfWork, segments, matcher);
    }
}
