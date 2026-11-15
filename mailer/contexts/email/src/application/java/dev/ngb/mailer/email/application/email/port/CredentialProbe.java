package dev.ngb.mailer.email.application.email.port;

/**
 * Proves a tenant's role can be assumed and can see SES, before a credential is stored.
 *
 * <p>It is a port rather than a method on the resolver so that onboarding can be verified with one call and a revoked
 * role can be found by a scheduled probe instead of by a wall of dead deliveries.
 */
public interface CredentialProbe {

    /**
     * Assumes the role and then reads the account, which is the cheapest call that proves both the trust policy and
     * the SES permission.
     */
    ProbeResult probe(String roleArn, String externalId, String region);

    /**
     * @param healthy whether the role was assumed and the account read
     * @param detail the provider's message when it was not
     * @param max24HourSend the account's send quota, so onboarding can record it; {@code null} when unknown
     * @param maxSendRate the account's rate ceiling; {@code null} when unknown
     */
    record ProbeResult(boolean healthy, String detail, Double max24HourSend, Double maxSendRate) {

        public static ProbeResult healthy(Double max24HourSend, Double maxSendRate) {
            return new ProbeResult(true, "ok", max24HourSend, maxSendRate);
        }

        public static ProbeResult failed(String detail) {
            return new ProbeResult(false, detail, null, null);
        }
    }
}
