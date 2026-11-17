package dev.ngb.workflow.runtime.adapter.runtime.persistence;

import static dev.ngb.workflow_jooq.Tables.WORKFLOW_TOKEN;

import dev.ngb.core.id.TenantId;
import dev.ngb.workflow.runtime.application.runtime.port.TokenRepository;
import dev.ngb.workflow.runtime.domain.token.Token;
import dev.ngb.workflow.runtime.domain.token.TokenStatus;
import dev.ngb.workflow_jooq.tables.records.WorkflowTokenRecord;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.JSONB;
import tools.jackson.databind.json.JsonMapper;

/**
 * The token store. The claim is one statement on purpose: the sub-select takes the due row with {@code FOR UPDATE SKIP
 * LOCKED} and the update sets the lease, so two workers asking at once cannot both win. Park and resume are conditional
 * updates on the status and the owner, which is what makes a timer and an event race for one token safely.
 */
public class JooqTokenRepository implements TokenRepository {

    private final DSLContext dsl;
    private final JsonMapper json;

    public JooqTokenRepository(DSLContext dsl, JsonMapper json) {
        this.dsl = dsl;
        this.json = json;
    }

    @Override
    public void add(Token token) {
        dsl.insertInto(WORKFLOW_TOKEN)
                .set(WORKFLOW_TOKEN.ID, token.id())
                .set(WORKFLOW_TOKEN.TENANT_ID, token.tenantId().value())
                .set(WORKFLOW_TOKEN.INSTANCE_ID, token.instanceId())
                .set(WORKFLOW_TOKEN.NODE_ID, token.nodeId())
                .set(WORKFLOW_TOKEN.STATUS, token.status().name())
                .set(WORKFLOW_TOKEN.PARENT_TOKEN_ID, token.parentTokenId())
                .set(WORKFLOW_TOKEN.BRANCH_KEY, token.branchKey())
                .set(WORKFLOW_TOKEN.ITERATION, token.iteration())
                .set(WORKFLOW_TOKEN.ATTEMPT, token.attempt())
                .set(WORKFLOW_TOKEN.AVAILABLE_AT, token.availableAt())
                .set(WORKFLOW_TOKEN.LEASE_OWNER, token.leaseOwner())
                .set(WORKFLOW_TOKEN.LEASE_UNTIL, token.leaseUntil())
                .set(WORKFLOW_TOKEN.RESUME_BRANCH, token.resumeBranch())
                .set(WORKFLOW_TOKEN.RESUME_PAYLOAD, payload(token.resumePayload()))
                .set(WORKFLOW_TOKEN.PAYLOAD, payload(token.payload()))
                .set(WORKFLOW_TOKEN.LOOP_NODE_ID, token.loopNodeId())
                .set(WORKFLOW_TOKEN.CREATED_AT, token.createdAt())
                .set(WORKFLOW_TOKEN.UPDATED_AT, token.updatedAt())
                .execute();
    }

    @Override
    public Optional<Token> claimNext(String owner, Instant now, Instant leaseUntil) {
        // The lock is taken by the select and held until this transaction ends; naming the row first keeps the update
        // to exactly one row even when many workers claim at the same instant.
        UUID id = dsl.select(WORKFLOW_TOKEN.ID)
                .from(WORKFLOW_TOKEN)
                .where(WORKFLOW_TOKEN.STATUS.eq(TokenStatus.READY.name()))
                .and(WORKFLOW_TOKEN.AVAILABLE_AT.le(now))
                .orderBy(WORKFLOW_TOKEN.AVAILABLE_AT, WORKFLOW_TOKEN.ID)
                .limit(1)
                .forUpdate()
                .skipLocked()
                .fetchOne(WORKFLOW_TOKEN.ID);
        if (id == null) {
            return Optional.empty();
        }
        return dsl.update(WORKFLOW_TOKEN)
                .set(WORKFLOW_TOKEN.STATUS, TokenStatus.RUNNING.name())
                .set(WORKFLOW_TOKEN.LEASE_OWNER, owner)
                .set(WORKFLOW_TOKEN.LEASE_UNTIL, leaseUntil)
                .set(WORKFLOW_TOKEN.ATTEMPT, WORKFLOW_TOKEN.ATTEMPT.plus(1))
                .set(WORKFLOW_TOKEN.UPDATED_AT, now)
                .where(WORKFLOW_TOKEN.ID.eq(id))
                .and(WORKFLOW_TOKEN.STATUS.eq(TokenStatus.READY.name()))
                .returning()
                .fetchOptional(row -> toDomain(row));
    }

    @Override
    public boolean finish(UUID tokenId, String owner, TokenStatus status, Instant now) {
        return dsl.update(WORKFLOW_TOKEN)
                        .set(WORKFLOW_TOKEN.STATUS, status.name())
                        .set(WORKFLOW_TOKEN.LEASE_OWNER, (String) null)
                        .set(WORKFLOW_TOKEN.LEASE_UNTIL, (Instant) null)
                        .set(WORKFLOW_TOKEN.UPDATED_AT, now)
                        .where(WORKFLOW_TOKEN.ID.eq(tokenId))
                        .and(WORKFLOW_TOKEN.STATUS.eq(TokenStatus.RUNNING.name()))
                        .and(WORKFLOW_TOKEN.LEASE_OWNER.eq(owner))
                        .execute()
                == 1;
    }

    @Override
    public boolean park(UUID tokenId, String owner, Instant now) {
        return dsl.update(WORKFLOW_TOKEN)
                        .set(WORKFLOW_TOKEN.STATUS, TokenStatus.WAITING.name())
                        .set(WORKFLOW_TOKEN.LEASE_OWNER, (String) null)
                        .set(WORKFLOW_TOKEN.LEASE_UNTIL, (Instant) null)
                        .set(WORKFLOW_TOKEN.UPDATED_AT, now)
                        .where(WORKFLOW_TOKEN.ID.eq(tokenId))
                        .and(WORKFLOW_TOKEN.STATUS.eq(TokenStatus.RUNNING.name()))
                        .and(WORKFLOW_TOKEN.LEASE_OWNER.eq(owner))
                        .execute()
                == 1;
    }

    @Override
    public boolean resume(UUID tokenId, String branch, Object payload, Instant now) {
        return dsl.update(WORKFLOW_TOKEN)
                        .set(WORKFLOW_TOKEN.STATUS, TokenStatus.READY.name())
                        .set(WORKFLOW_TOKEN.LEASE_OWNER, (String) null)
                        .set(WORKFLOW_TOKEN.LEASE_UNTIL, (Instant) null)
                        .set(WORKFLOW_TOKEN.RESUME_BRANCH, branch)
                        .set(WORKFLOW_TOKEN.RESUME_PAYLOAD, payload(payload))
                        .set(WORKFLOW_TOKEN.AVAILABLE_AT, now)
                        .set(WORKFLOW_TOKEN.UPDATED_AT, now)
                        .where(WORKFLOW_TOKEN.ID.eq(tokenId))
                        .and(WORKFLOW_TOKEN.STATUS.eq(TokenStatus.WAITING.name()))
                        .execute()
                == 1;
    }

    @Override
    public long liveCount(UUID instanceId) {
        return dsl.fetchCount(
                WORKFLOW_TOKEN,
                WORKFLOW_TOKEN
                        .INSTANCE_ID
                        .eq(instanceId)
                        .and(WORKFLOW_TOKEN.STATUS.in(
                                TokenStatus.READY.name(), TokenStatus.RUNNING.name(), TokenStatus.WAITING.name())));
    }

    @Override
    public long activeCount(UUID instanceId) {
        return dsl.fetchCount(
                WORKFLOW_TOKEN,
                WORKFLOW_TOKEN
                        .INSTANCE_ID
                        .eq(instanceId)
                        .and(WORKFLOW_TOKEN.STATUS.in(TokenStatus.READY.name(), TokenStatus.RUNNING.name())));
    }

    @Override
    public int cancelLive(UUID instanceId, UUID exceptTokenId, Instant now) {
        return dsl.update(WORKFLOW_TOKEN)
                .set(WORKFLOW_TOKEN.STATUS, TokenStatus.CANCELLED.name())
                .set(WORKFLOW_TOKEN.LEASE_OWNER, (String) null)
                .set(WORKFLOW_TOKEN.LEASE_UNTIL, (Instant) null)
                .set(WORKFLOW_TOKEN.UPDATED_AT, now)
                .where(WORKFLOW_TOKEN.INSTANCE_ID.eq(instanceId))
                .and(WORKFLOW_TOKEN.STATUS.in(
                        TokenStatus.READY.name(), TokenStatus.RUNNING.name(), TokenStatus.WAITING.name()))
                .and(WORKFLOW_TOKEN.ID.ne(exceptTokenId))
                .execute();
    }

    @Override
    public int requeueExpired(Instant now) {
        return dsl.update(WORKFLOW_TOKEN)
                .set(WORKFLOW_TOKEN.STATUS, TokenStatus.READY.name())
                .set(WORKFLOW_TOKEN.LEASE_OWNER, (String) null)
                .set(WORKFLOW_TOKEN.LEASE_UNTIL, (Instant) null)
                .set(WORKFLOW_TOKEN.AVAILABLE_AT, now)
                .set(WORKFLOW_TOKEN.UPDATED_AT, now)
                .where(WORKFLOW_TOKEN.STATUS.eq(TokenStatus.RUNNING.name()))
                .and(WORKFLOW_TOKEN.LEASE_UNTIL.lt(now))
                .execute();
    }

    private JSONB payload(Object payload) {
        return payload == null ? null : JSONB.valueOf(json.writeValueAsString(payload));
    }

    private Object payload(JSONB value) {
        return value == null ? null : json.readValue(value.data(), Object.class);
    }

    private Token toDomain(WorkflowTokenRecord row) {
        return new Token(
                row.getId(),
                new TenantId(row.getTenantId()),
                row.getInstanceId(),
                row.getNodeId(),
                TokenStatus.valueOf(row.getStatus()),
                row.getParentTokenId(),
                row.getBranchKey(),
                row.getIteration(),
                row.getAttempt(),
                row.getAvailableAt(),
                row.getLeaseOwner(),
                row.getLeaseUntil(),
                row.getResumeBranch(),
                payload(row.getResumePayload()),
                payload(row.getPayload()),
                row.getLoopNodeId(),
                row.getCreatedAt(),
                row.getUpdatedAt());
    }
}
