package dev.ngb.mailer.boot.jobs;

import dev.ngb.mailer.boot.config.MailerSettings;
import dev.ngb.mailer.email.application.email.command.CredentialHealthUseCase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Re-probes stale tenant credentials, so a revoked role is found by this job instead of by a wall of dead deliveries.
 *
 * <p>It ships with {@code TENANT} mode rather than later: the mode's whole risk is a role that stops working silently,
 * and a probe that runs on a schedule is the only thing that turns that into a warning.
 */
@Profile("worker")
@Component
@ConditionalOnProperty(name = "mailer.credentials.health-enabled", havingValue = "true", matchIfMissing = true)
public class CredentialHealthJob {

    private static final Logger LOG = LoggerFactory.getLogger(CredentialHealthJob.class);

    private final TenantSweep sweep;
    private final CredentialHealthUseCase useCase;
    private final MailerSettings settings;

    CredentialHealthJob(TenantSweep sweep, CredentialHealthUseCase useCase, MailerSettings settings) {
        this.sweep = sweep;
        this.useCase = useCase;
        this.settings = settings;
    }

    @Scheduled(fixedDelayString = "${mailer.credentials.health-interval:PT15M}")
    public void run() {
        sweep.forEachActiveTenant(tenantId -> {
            CredentialHealthUseCase.Swept swept = useCase.runOnce(settings.credentials().staleAfter());
            if (swept.failed() > 0) {
                LOG.warn("tenant {}: {} of {} credential probe(s) failed", tenantId, swept.failed(), swept.checked());
            }
        });
    }
}
