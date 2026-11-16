package dev.ngb.loyalty.points.application.expiry.command;

import static org.assertj.core.api.Assertions.assertThat;

import dev.ngb.core.id.TenantId;
import dev.ngb.loyalty.support.ExpiryApiTest;
import dev.ngb.loyalty.support.ParallelCalls;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * A spend and the expiry run reaching for the same member at the same moment.
 *
 * <p>The member's row is the lock both take first, so the two run one after the other and the second sees what the first
 * left. Each pass below states the properties that must hold whichever of them went first: no lot holds a negative
 * number, the lots still sum to what the ledger says they should, each due lot is written off once, and the balance the
 * API answers with is the same before and after the run.
 */
class ExpiryRaceTest extends ExpiryApiTest {

    /** As many passes as the plan asks for; each one races a spend against a write-off on a fresh tenant. */
    private static final int ITERATIONS = 100;

    @Test
    void aSpendRacingTheWriteOffNeverOverspendsAndLeavesTheLedgerBalanced() {
        List<String> failures = new ArrayList<>();
        for (int i = 0; i < ITERATIONS; i++) {
            TenantId tenant = new TenantId(UUID.randomUUID());
            Instant now = Instant.now();
            seedLot(tenant, "R-1", 30L, now.minus(1, ChronoUnit.DAYS));
            seedLot(tenant, "R-1", 25L, now.plus(20, ChronoUnit.DAYS));
            seedLot(tenant, "J-1", 30L, now.minus(1, ChronoUnit.DAYS));
            seedLot(tenant, "J-1", 25L, now.plus(20, ChronoUnit.DAYS));
            long racingBefore = available(tenant, "R-1");
            long quietBefore = available(tenant, "J-1");

            List<Object> answers = ParallelCalls.run(2, 2, index -> index == 0
                    ? postRedemption(tenant, "R-1", "K-1", 25, "RECEIPT-1")
                    : runExpiry(tenant));

            ApiResponse spend = (ApiResponse) answers.get(0);
            if (spend.status() != 201 && spend.status() != 200) {
                failures.add("pass " + i + ": the spend answered " + spend.status() + " " + spend.body());
                continue;
            }
            if (scalar(tenant, "select count(*) from point_lots where remaining < 0") != 0) {
                failures.add("pass " + i + ": a lot holds a negative number");
            }
            if (scalar(tenant, "select coalesce(sum(points), 0) from ledger_entries where kind = 'EXPIRE'") != 60) {
                failures.add("pass " + i + ": the two due lots were not written off exactly once each");
            }
            if (scalar(tenant, "select count(*) from ledger_entries where kind = 'EXPIRE'") != 2) {
                failures.add("pass " + i + ": the write-off did not write one entry per lot");
            }
            long lots = scalar(tenant, "select coalesce(sum(remaining), 0) from point_lots where member_id = "
                    + "(select id from members where external_ref = 'R-1')");
            long ledger = scalar(
                    tenant,
                    "select coalesce(sum(case when kind in ('EARN', 'REDEEM_REVERSAL') then points "
                            + "else -points end), 0) from ledger_entries where member_id = "
                            + "(select id from members where external_ref = 'R-1')");
            if (lots != ledger) {
                failures.add("pass " + i + ": the lots hold " + lots + " where the ledger says " + ledger);
            }
            if (available(tenant, "R-1") != lots) {
                failures.add("pass " + i + ": the balance is not the lots the member has left");
            }
            if (racingBefore != 25) {
                failures.add("pass " + i + ": the member could spend " + racingBefore + " before the race");
            }
            if (available(tenant, "J-1") != quietBefore || quietBefore != 25) {
                failures.add("pass " + i + ": the run changed a balance it must not touch");
            }
        }
        assertThat(failures).as("every pass kept the rules").isEmpty();
    }
}
