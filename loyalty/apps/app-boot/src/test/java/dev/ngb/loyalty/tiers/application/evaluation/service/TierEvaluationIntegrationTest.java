package dev.ngb.loyalty.tiers.application.evaluation.service;

import static org.assertj.core.api.Assertions.assertThat;

import dev.ngb.core.id.TenantId;
import dev.ngb.loyalty.support.EarnVectors;
import dev.ngb.loyalty.support.PointsApiTest;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * The two paths a member's tier can take: up in the transaction of the earn that crossed a threshold, and down only in
 * the scheduled evaluation.
 *
 * <p>The pass is driven directly rather than waited for, so each case reads one decision at a time. The batch size is
 * small here so a tier change walks several batches and a run that stops halfway resumes where it stopped.
 */
class TierEvaluationIntegrationTest extends PointsApiTest {

    private static final EarnVectors.Program PROGRAM = new EarnVectors.Program("USD", 100L, "UTC", 12, 30, 12);
    private static final List<EarnVectors.Tier> TIERS = List.of(
            new EarnVectors.Tier("BRONZE", 0L, new BigDecimal("1.00")),
            new EarnVectors.Tier("SILVER", 1000L, new BigDecimal("1.50")),
            new EarnVectors.Tier("GOLD", 5000L, new BigDecimal("2.00")));

    private static final int BATCH_SIZE = 100;

    @DynamicPropertySource
    static void smallBatches(DynamicPropertyRegistry registry) {
        registry.add("loyalty.tier-evaluation.batch-size", () -> Integer.toString(BATCH_SIZE));
    }

    @Autowired TierEvaluationService evaluation;

    @Test
    void theCrossingOrderRaisesTheTierInTheSameCall() {
        TenantId tenant = tenant();
        seedRules(tenant, rules());
        assertThat(getMember(tenant, "M-1").status()).as("the member does not exist yet").isEqualTo(404);

        assertThat(putOrder(tenant, "O-1", orderBody("M-1", "USD", occurredAt(Duration.ofDays(2)), "ST-01", 900)).status())
                .isEqualTo(201);
        assertThat(tierOf(tenant, "M-1")).as("just under the threshold").isEqualTo("BRONZE");

        assertThat(putOrder(tenant, "O-2", orderBody("M-1", "USD", occurredAt(Duration.ofDays(1)), "ST-01", 200)).status())
                .isEqualTo(201);
        assertThat(tierOf(tenant, "M-1"))
                .as("the earn that crossed the threshold moved the member before it committed")
                .isEqualTo("SILVER");
        assertThat(nextTierOf(tenant, "M-1")).isEqualTo("GOLD");
    }

    @Test
    void aReturnDoesNotLowerTheTierUntilTheEvaluationRuns() {
        TenantId tenant = tenant();
        seedRules(tenant, rules());
        putOrder(tenant, "O-1", orderBody("M-2", "USD", occurredAt(Duration.ofDays(3)), "ST-01", 2000));
        assertThat(tierOf(tenant, "M-2")).isEqualTo("SILVER");

        ApiResponse returned =
                putReturn(tenant, "O-1", "R-1", returnBody(occurredAt(Duration.ofDays(1)), BigDecimal.ONE, 2000));
        assertThat(returned.status()).isEqualTo(201);
        assertThat(tierOf(tenant, "M-2")).as("a return never lowers a tier itself").isEqualTo("SILVER");
        assertThat(reviewAt(tenant, "M-2"))
                .as("the return asks the next evaluation to look at the member")
                .isNotNull()
                .isBeforeOrEqualTo(Instant.now());

        TierEvaluationService.Report report = inTenant(tenant, () -> evaluation.reviewDue());

        assertThat(report.changed()).as("the pass is where the member falls").isEqualTo(1);
        assertThat(tierOf(tenant, "M-2")).isEqualTo("BRONZE");
    }

    @Test
    void aChangedTierListReachesEveryMemberAndARestartResumesInsteadOfRepeating() {
        TenantId tenant = tenant();
        seedRules(tenant, rules());
        bulkMembers(tenant, 300);

        Instant first = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        Optional<UUID> cursor = inTenant(tenant, () -> evaluation.remark(null, first));
        assertThat(cursor).as("more members than one batch leaves a cursor").isPresent();
        assertThat(markedAt(tenant, first)).as("one batch marked").isEqualTo(BATCH_SIZE);
        assertThat(tiersDirty(tenant)).as("the pass is unfinished, so the list stays marked").isTrue();

        // A run that stopped here is followed by a new one that starts from the mark: the members already reached keep
        // their instant instead of being moved to the new run's.
        Instant second = first.plusSeconds(60);
        Optional<UUID> next = inTenant(tenant, () -> evaluation.remark(null, second));
        assertThat(markedAt(tenant, first)).as("the first batch was not repeated").isEqualTo(BATCH_SIZE);
        assertThat(markedAt(tenant, second)).as("the restart marked nothing it had already done").isZero();

        while (next.isPresent()) {
            Optional<UUID> from = next;
            next = inTenant(tenant, () -> evaluation.remark(from.get(), second));
        }

        assertThat(tiersDirty(tenant)).as("every member was reached, so the list owes no further pass").isFalse();
        assertThat(marked(tenant)).as("all three hundred members are due").isEqualTo(300);
    }

    @Test
    void aChangedTierListMovesTheMembersItShouldInThePass() {
        TenantId tenant = tenant();
        seedRules(tenant, rules());
        putOrder(tenant, "O-1", orderBody("M-3", "USD", occurredAt(Duration.ofDays(2)), "ST-01", 1200));
        assertThat(tierOf(tenant, "M-3")).isEqualTo("SILVER");

        // The Tenant raises silver's threshold above what the member spent, so the member no longer qualifies for it.
        seedTiers(tenant, "*", List.of(
                new EarnVectors.Tier("BRONZE", 0L, new BigDecimal("1.00")),
                new EarnVectors.Tier("SILVER", 4000L, new BigDecimal("1.50")),
                new EarnVectors.Tier("GOLD", 9000L, new BigDecimal("2.00"))));
        assertThat(tiersDirty(tenant)).as("a changed list owes every member a pass").isTrue();

        TierEvaluationService.Report report = inTenant(tenant, () -> evaluation.reviewDue());

        assertThat(report.changed()).as("the member fell once the list said it did not qualify").isEqualTo(1);
        assertThat(tierOf(tenant, "M-3")).isEqualTo("BRONZE");
        assertThat(tiersDirty(tenant)).isFalse();
    }

    @Test
    void aMemberWithNothingInTheWindowFallsToTheBaseTierAndKeepsNoReview() {
        TenantId tenant = tenant();
        seedRules(tenant, rules());
        seedMemberDue(tenant, "M-4", "SILVER");

        TierEvaluationService.Report report = inTenant(tenant, () -> evaluation.reviewDue());

        assertThat(report.changed()).isEqualTo(1);
        assertThat(tierOf(tenant, "M-4")).as("nothing in the window reaches silver").isEqualTo("BRONZE");
        assertThat(reviewAt(tenant, "M-4")).as("nothing can change the answer on its own").isNull();
        assertThat(inTenant(tenant, () -> evaluation.reviewDue()).reviewed())
                .as("a member with no review instant is not looked at again")
                .isZero();
    }

    @Test
    void theReviewInstantIsWhenTheOldestOrderLeavesTheWindow() {
        TenantId tenant = tenant();
        seedRules(tenant, rules());
        Instant orderedAt = Instant.now().minus(Duration.ofDays(5)).truncatedTo(ChronoUnit.MICROS);

        putOrder(tenant, "O-1", orderBody("M-5", "USD", orderedAt.toString(), "ST-01", 1500));

        assertThat(reviewAt(tenant, "M-5"))
                .as("a twelve-month window keeps the member due when the order leaves it")
                .isEqualTo(orderedAt.atZone(ZoneOffset.UTC).plusMonths(12).toInstant());
    }

    // ------------------------------------------------------------------ helpers

    private static TenantId tenant() {
        return new TenantId(UUID.randomUUID());
    }

    private static EarnVectors.Rules rules() {
        return new EarnVectors.Rules(PROGRAM, TIERS, List.of());
    }

    private static String occurredAt(Duration ago) {
        return Instant.now().minus(ago).toString();
    }

    private String tierOf(TenantId tenant, String externalRef) {
        ApiResponse member = getMember(tenant, externalRef);
        if (member.status() != 200 || member.json().get("tier") == null) {
            return null;
        }
        return member.json().get("tier").get("code").asString();
    }

    private String nextTierOf(TenantId tenant, String externalRef) {
        ApiResponse member = getMember(tenant, externalRef);
        return member.json().get("nextTier") == null
                ? null
                : member.json().get("nextTier").get("code").asString();
    }

    /** The member's review instant, read straight from the row so the test sees exactly what the pass would. */
    private Instant reviewAt(TenantId tenant, String externalRef) {
        return inTenant(tenant, () -> unitOfWork.transactional(() -> {
            var row = dsl.fetchOne("select tier_review_at from members where external_ref = ?", externalRef);
            return row == null ? null : row.get(0, Instant.class);
        }));
    }

    private boolean tiersDirty(TenantId tenant) {
        return scalar(tenant, "select count(*) from loyalty_programs where tiers_dirty") == 1;
    }

    private long markedAt(TenantId tenant, Instant at) {
        return inTenant(tenant, () -> unitOfWork.transactional(() -> {
            var row = dsl.fetchOne("select count(*) from members where tier_review_at = (?::timestamptz)", at);
            return row == null ? 0L : row.get(0, Long.class);
        }));
    }

    private long marked(TenantId tenant) {
        return scalar(tenant, "select count(*) from members where tier_review_at is not null");
    }

    private void bulkMembers(TenantId tenant, int howMany) {
        inTenant(tenant, () -> unitOfWork.transactional(() -> dsl.execute("""
                insert into members (tenant_id, id, external_ref, status, created_at)
                select ?, gen_random_uuid(), 'BULK-' || g, 'ACTIVE', now() from generate_series(1, ?) g
                """, tenant.value(), howMany)));
    }

    /** A member that already holds a tier and is due for review, which no endpoint of this feature can arrange. */
    private void seedMemberDue(TenantId tenant, String externalRef, String tierCode) {
        inTenant(tenant, () -> unitOfWork.transactional(() -> dsl.execute(
                "insert into members (tenant_id, id, external_ref, tier_code, status, tier_review_at, created_at) "
                        + "values (?, ?, ?, ?, 'ACTIVE', now() - interval '1 minute', now())",
                tenant.value(),
                UUID.randomUUID(),
                externalRef,
                tierCode)));
    }
}
