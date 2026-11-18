package dev.ngb.loyalty.points.adapter.member.web;

import static org.assertj.core.api.Assertions.assertThat;

import dev.ngb.core.id.TenantId;
import dev.ngb.loyalty.support.EarnVectors;
import dev.ngb.loyalty.support.PointsApiTest;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The member endpoints: the enrolment that may come before the first order, the same member being reached by the same
 * reference however it was created, the reference an erased member releases, and the balance and ledger a read answers.
 */
class MemberEnrolIntegrationTest extends PointsApiTest {

    private static final EarnVectors.Program PROGRAM =
            new EarnVectors.Program("USD", 100L, "UTC", 12, 30, 12);
    private static final List<EarnVectors.Tier> TIERS = List.of(
            new EarnVectors.Tier("BRONZE", 0L, new java.math.BigDecimal("1.00")),
            new EarnVectors.Tier("SILVER", 100000L, new java.math.BigDecimal("1.50")));

    @Test
    void anEnrolmentCreatesOnceThenFindsTheSameMember() {
        TenantId tenant = new TenantId(UUID.randomUUID());

        ApiResponse created = putMember(tenant, "M-1", "Ada");
        ApiResponse again = putMember(tenant, "M-1", "Ada");

        assertThat(created.status()).isEqualTo(201);
        assertThat(again.status()).as("the second call enrols nothing").isEqualTo(200);
        assertThat(again.json().get("memberId").asString())
                .isEqualTo(created.json().get("memberId").asString());
        assertThat(created.json().get("externalRef").asString()).isEqualTo("M-1");
        assertThat(created.json().get("displayName").asString()).isEqualTo("Ada");
        assertThat(created.json().get("status").asString()).isEqualTo("ACTIVE");
        assertThat(created.json().get("available").asLong()).isZero();
        assertThat(count(tenant, "members")).isEqualTo(1);
    }

    @Test
    void theReferenceIsTrimmedAndAnEmptyOrOverlongOneIsRefused() {
        TenantId tenant = new TenantId(UUID.randomUUID());

        assertThat(putMember(tenant, "%20M-trim%20", null).json().get("externalRef").asString())
                .isEqualTo("M-trim");
        assertThat(putMember(tenant, "%20%20", null).status()).isEqualTo(422);
        assertThat(putMember(tenant, "%20%20", null).code()).isEqualTo("VALIDATION_FAILED");
        assertThat(putMember(tenant, "M-" + "x".repeat(200), null).status()).isEqualTo(422);
    }

    @Test
    void anOrderForAnEnrolledReferenceUsesThatMember() {
        TenantId tenant = new TenantId(UUID.randomUUID());
        seedRules(tenant, new EarnVectors.Rules(PROGRAM, TIERS, List.of()));
        String enrolled = putMember(tenant, "M-2", null).json().get("memberId").asString();

        ApiResponse order = putOrder(tenant, "O-1", orderBody("M-2", "USD", "2026-03-02T10:00:00Z", "ST-01", 1000));

        assertThat(order.status()).isEqualTo(201);
        assertThat(order.json().get("pointsGranted").asLong()).isEqualTo(10);
        assertThat(getMember(tenant, "M-2").json().get("memberId").asString()).isEqualTo(enrolled);
        assertThat(count(tenant, "members")).as("the order enrolled nobody new").isEqualTo(1);
    }

    @Test
    void theFirstOrderEnrolsTheMember() {
        TenantId tenant = new TenantId(UUID.randomUUID());
        seedRules(tenant, new EarnVectors.Rules(PROGRAM, TIERS, List.of()));

        ApiResponse order = putOrder(tenant, "O-2", orderBody("M-3", "USD", "2026-03-02T10:00:00Z", "ST-01", 2500));

        assertThat(order.status()).isEqualTo(201);
        ApiResponse member = getMember(tenant, "M-3");
        assertThat(member.status()).isEqualTo(200);
        assertThat(member.json().get("available").asLong()).isEqualTo(25);
        assertThat(member.json().get("tier").get("code").asString()).isEqualTo("BRONZE");
        assertThat(member.json().get("nextTier").get("code").asString()).isEqualTo("SILVER");
        assertThat(member.json().get("pointsExpiring")).as("a 12-month lot is outside the default 30-day notice window").isEmpty();
    }

    @Test
    void anErasedMemberReleasesItsReferenceSoTheSameReferenceStartsANewMember() {
        TenantId tenant = new TenantId(UUID.randomUUID());
        String first = putMember(tenant, "M-4", null).json().get("memberId").asString();
        inTenant(tenant, () -> unitOfWork.transactional(() -> dsl.execute(
                "update members set external_ref = null, status = 'ERASED', version = version + 1 "
                        + "where external_ref = ?",
                "M-4")));

        assertThat(getMember(tenant, "M-4").status()).as("an erased member is not readable by its old reference")
                .isEqualTo(404);
        ApiResponse reEnrolled = putMember(tenant, "M-4", null);

        assertThat(reEnrolled.status()).isEqualTo(201);
        assertThat(reEnrolled.json().get("memberId").asString()).isNotEqualTo(first);
        assertThat(count(tenant, "members")).isEqualTo(2);
    }

    @Test
    void anUnknownMemberIsNotFound() {
        TenantId tenant = new TenantId(UUID.randomUUID());

        ApiResponse member = getMember(tenant, "M-nobody");
        ApiResponse ledger = getLedger(tenant, "M-nobody", 10);

        assertThat(member.status()).isEqualTo(404);
        assertThat(member.code()).isEqualTo("MEMBER_NOT_FOUND");
        assertThat(ledger.status()).isEqualTo(404);
        assertThat(ledger.code()).isEqualTo("MEMBER_NOT_FOUND");
    }

    @Test
    void theLedgerListsWhatTheOrderEarned() {
        TenantId tenant = new TenantId(UUID.randomUUID());
        seedRules(tenant, new EarnVectors.Rules(PROGRAM, TIERS, List.of()));
        putOrder(tenant, "O-3", orderBody("M-5", "USD", "2026-03-02T10:00:00Z", "ST-01", 4000));

        ApiResponse ledger = getLedger(tenant, "M-5", 10);

        assertThat(ledger.status()).isEqualTo(200);
        assertThat(ledger.json().get("data")).hasSize(1);
        assertThat(ledger.json().get("data").get(0).get("kind").asString()).isEqualTo("EARN");
        assertThat(ledger.json().get("data").get(0).get("points").asLong()).isEqualTo(40);
        assertThat(ledger.json().get("data").get(0).get("orderRef").asString()).isEqualTo("O-3");
    }

    private int count(TenantId tenant, String table) {
        return inTenant(tenant, () -> unitOfWork.transactional(
                () -> dsl.fetchOne("select count(*) from " + table).get(0, Integer.class)));
    }
}
