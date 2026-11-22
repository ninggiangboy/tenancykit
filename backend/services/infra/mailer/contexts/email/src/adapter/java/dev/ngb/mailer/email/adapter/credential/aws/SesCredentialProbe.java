package dev.ngb.mailer.email.adapter.credential.aws;

import dev.ngb.mailer.email.application.email.port.CredentialProbe;
import software.amazon.awssdk.auth.credentials.AwsSessionCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sesv2.SesV2Client;
import software.amazon.awssdk.services.sesv2.model.GetAccountResponse;
import software.amazon.awssdk.services.sesv2.model.SendQuota;
import software.amazon.awssdk.services.sts.StsClient;
import software.amazon.awssdk.services.sts.model.Credentials;

/**
 * Proves a tenant credential before it is stored: assume the role, then read the account.
 *
 * <p>{@code GetAccount} is the cheapest call that proves both the trust policy and the SES permission, and its response
 * carries the send quota, so onboarding records the quota without a second call. SESv2 has no {@code GetSendQuota} —
 * that action exists only in SES v1.
 *
 * <p>The probe is one-shot: it builds its own clients and caches nothing, which is what a verification wants.
 */
public class SesCredentialProbe implements CredentialProbe {

    /** Role chaining caps the session at one hour; a probe needs far less. */
    private static final int PROBE_SESSION_SECONDS = 900;

    @Override
    public ProbeResult probe(String roleArn, String externalId, String region) {
        try {
            Credentials assumed = assume(roleArn, externalId, region);
            try (SesV2Client ses = ses(assumed, region)) {
                GetAccountResponse account = ses.getAccount(request -> {});
                SendQuota quota = account.sendQuota();
                return ProbeResult.healthy(
                        quota == null ? null : quota.max24HourSend(), quota == null ? null : quota.maxSendRate());
            }
        } catch (RuntimeException failure) {
            // A revoked role, a wrong external id or a missing permission all arrive here; none of them is an exception
            // the caller should have to interpret.
            return ProbeResult.failed(failure.getMessage());
        }
    }

    private static Credentials assume(String roleArn, String externalId, String region) {
        try (StsClient sts = StsClient.builder().region(Region.of(region)).build()) {
            return sts.assumeRole(request -> request
                            .roleArn(roleArn)
                            .externalId(externalId)
                            .roleSessionName("mailer-probe")
                            .durationSeconds(PROBE_SESSION_SECONDS))
                    .credentials();
        }
    }

    private static SesV2Client ses(Credentials assumed, String region) {
        AwsSessionCredentials credentials = AwsSessionCredentials.create(
                assumed.accessKeyId(), assumed.secretAccessKey(), assumed.sessionToken());
        return SesV2Client.builder()
                .region(Region.of(region))
                .credentialsProvider(StaticCredentialsProvider.create(credentials))
                .build();
    }
}
