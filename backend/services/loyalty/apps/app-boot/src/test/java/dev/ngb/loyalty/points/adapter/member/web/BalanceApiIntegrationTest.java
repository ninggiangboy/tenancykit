package dev.ngb.loyalty.points.adapter.member.web;

import static org.assertj.core.api.Assertions.assertThat;

import dev.ngb.core.id.TenantId;
import dev.ngb.loyalty.shared.LoyaltyPermissions;
import dev.ngb.loyalty.support.EarnVectors;
import dev.ngb.loyalty.support.PointsApiTest;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

/**
 * The member read a POS asks before it takes points: the balance, the debt, the tier above and the points that expire
 * inside a window.
 *
 * <p>The balance is never stored, so the cases below are the consequences of that one decision: a debt makes it negative,
 * an expired lot counts for nothing whether or not anything swept it, and the window decides only which lots are listed.
 */
class BalanceApiIntegrationTest extends PointsApiTest {

    private static final String ORDERED_AT = "2026-03-02T10:00:00Z";

    private static final EarnVectors.Program PROGRAM = new EarnVectors.Program("USD", 100L, "UTC", 12, 30, 12);
    private static final List<EarnVectors.Tier> TIERS = List.of(
            new EarnVectors.Tier("BRONZE", 0L, new BigDecimal("1.00")),
            new EarnVectors.Tier("SILVER", 100000L, new BigDecimal("1.50")));

    @Test
    void aBalanceIsNegativeWhileTheDebtExceedsTheLots() {
        TenantId tenant = tenant();
        seedRules(tenant, rules());
        seedMember(tenant, "M-1", "BRONZE", 10L);

        ApiResponse member = getMember(tenant, "M-1");

        assertThat(member.status()).isEqualTo(200);
        assertThat(member.json().get("available").asLong()).as("nothing earned against ten owed").isEqualTo(-10);
        assertThat(member.json().get("debt").asLong()).isEqualTo(10);
        assertThat(member.json().get("tier").get("code").asString()).isEqualTo("BRONZE");
        assertThat(member.json().get("nextTier").get("code").asString()).isEqualTo("SILVER");
        assertThat(member.json().get("nextTier").get("minSpend").asLong()).isEqualTo(100000);
    }

    @Test
    void theWindowDecidesWhichLotsAreListedAndNeverWhatIsAvailable() {
        TenantId tenant = tenant();
        seedRules(tenant, rules());
        putOrder(tenant, "O-1", orderBody("M-1", "USD", ORDERED_AT, "ST-01", 5000));
        inTenant(tenant, () -> unitOfWork.transactional(
                () -> dsl.execute("update point_lots set expires_at = now() + interval '10 days'")));

        ApiResponse byDefault = getMember(tenant, "M-1");
        ApiResponse insideAWeek = getMemberWithin(tenant, "M-1", 7);
        ApiResponse aYear = getMemberWithin(tenant, "M-1", 365);

        assertThat(byDefault.json().get("pointsExpiring")).as("the default window is thirty days").hasSize(1);
        assertThat(byDefault.json().get("pointsExpiring").get(0).get("points").asLong()).isEqualTo(50);
        assertThat(insideAWeek.json().get("pointsExpiring")).as("a lot ten days out is outside a seven-day window").isEmpty();
        assertThat(aYear.json().get("pointsExpiring")).hasSize(1);
        assertThat(insideAWeek.json().get("available").asLong())
                .as("the window lists lots and never changes the balance")
                .isEqualTo(50);
    }

    @Test
    void aWindowOutsideTheDocumentedRangeIsInvalid() {
        TenantId tenant = tenant();
        putMember(tenant, "M-1", null);

        assertThat(getMemberWithin(tenant, "M-1", 366).status()).isEqualTo(422);
        assertThat(getMemberWithin(tenant, "M-1", 366).code()).isEqualTo("VALIDATION_FAILED");
        assertThat(getMemberWithin(tenant, "M-1", -1).status()).isEqualTo(422);
    }

    @Test
    void anExpiredButUnsweptLotIsNeitherCountedNorSpent() {
        TenantId tenant = tenant();
        seedRules(tenant, rules());
        putOrder(tenant, "O-1", orderBody("M-1", "USD", ORDERED_AT, "ST-01", 5000));
        inTenant(tenant, () -> unitOfWork.transactional(
                () -> dsl.execute("update point_lots set expires_at = now() - interval '1 day'")));

        ApiResponse member = getMember(tenant, "M-1");

        assertThat(member.json().get("available").asLong())
                .as("the lot still holds fifty points, none of them live")
                .isZero();
        assertThat(member.json().get("pointsExpiring")).isEmpty();
        assertThat(scalar(tenant, "select sum(remaining) from point_lots")).as("nothing swept the lot").isEqualTo(50);
        assertThat(postRedemption(tenant, "M-1", "K-1", 1, null).code()).isEqualTo("INSUFFICIENT_POINTS");
    }

    @Test
    void anUnknownOrErasedMemberIsNotFound() {
        TenantId tenant = tenant();
        putMember(tenant, "M-1", null);
        inTenant(tenant, () -> unitOfWork.transactional(() -> dsl.execute(
                "update members set status = 'ERASED', external_ref = null, version = version + 1")));

        assertThat(getMember(tenant, "M-nobody").status()).isEqualTo(404);
        assertThat(getMember(tenant, "M-nobody").code()).isEqualTo("MEMBER_NOT_FOUND");
        assertThat(getMember(tenant, "M-1").status()).as("an erased member released its reference").isEqualTo(404);
    }

    @Test
    void aBalanceOverThousandsOfLotsStillAnswersPromptly() {
        TenantId tenant = tenant();
        seedRules(tenant, rules());
        seedMember(tenant, "M-1", "BRONZE", 0L);
        inTenant(tenant, () -> unitOfWork.transactional(() -> dsl.execute(
                "insert into point_lots (tenant_id, id, member_id, entry_id, earned, remaining, earned_at, expires_at)"
                        + " select ?, gen_random_uuid(), (select id from members where external_ref = 'M-1'),"
                        + " gen_random_uuid(), 1, 1, now(), now() + interval '30 days'"
                        + " from generate_series(1, 5000)",
                tenant.value())));

        long started = System.nanoTime();
        ApiResponse member = getMember(tenant, "M-1");
        long took = System.nanoTime() - started;

        assertThat(member.status()).isEqualTo(200);
        assertThat(member.json().get("available").asLong()).isEqualTo(5000);
        assertThat(Duration.ofNanos(took)).as("the balance is a sum over the live-lots index")
                .isLessThan(Duration.ofSeconds(5));
    }

    private ApiResponse getMemberWithin(TenantId tenant, String externalRef, int expiringWithinDays) {
        return call(
                tenant,
                MockMvcRequestBuilders.get("/api/v1/members/" + externalRef)
                        .param("expiringWithinDays", Integer.toString(expiringWithinDays)),
                LoyaltyPermissions.MEMBER_READ);
    }

    private static TenantId tenant() {
        return new TenantId(UUID.randomUUID());
    }

    private static EarnVectors.Rules rules() {
        return new EarnVectors.Rules(PROGRAM, TIERS, List.of());
    }
}
