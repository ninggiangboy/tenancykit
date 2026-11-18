package dev.ngb.platform.boot.jobs;

import java.time.Duration;

/**
 * One named job's lease across every instance: the holder runs the job, and a lease that stopped renewing frees it.
 */
public interface JobLease {

    /** @return true when this instance may run the job for {@code duration} */
    boolean tryAcquire(String job, Duration duration);
}
