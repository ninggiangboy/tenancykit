package dev.ngb.booking.gate.application.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** The per-target admission cap takes bounded permits and always gives one back. */
class AdmissionControlTest {

    @Test
    void aTargetTakesAtMostItsPermitsAndReleasesThem() {
        AdmissionControl admissions = new AdmissionControl(2);

        assertThat(admissions.tryAcquire("offering:a")).isTrue();
        assertThat(admissions.tryAcquire("offering:a")).isTrue();
        assertThat(admissions.tryAcquire("offering:a")).as("the third in-flight hold is refused").isFalse();
        admissions.release("offering:a");
        assertThat(admissions.tryAcquire("offering:a")).isTrue();
    }

    @Test
    void targetsDoNotShareTheirPermits() {
        AdmissionControl admissions = new AdmissionControl(1);

        assertThat(admissions.tryAcquire("session:a")).isTrue();
        assertThat(admissions.tryAcquire("session:b")).isTrue();
        assertThat(admissions.tryAcquire("session:a")).isFalse();
    }
}
