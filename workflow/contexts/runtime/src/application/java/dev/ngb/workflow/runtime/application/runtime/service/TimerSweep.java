package dev.ngb.workflow.runtime.application.runtime.service;

import dev.ngb.core.runtime.UnitOfWork;
import dev.ngb.workflow.runtime.application.runtime.port.TimerRepository;
import dev.ngb.workflow.runtime.application.runtime.port.TokenRepository;
import dev.ngb.workflow.runtime.application.runtime.port.WaitSubscriptionRepository;
import dev.ngb.workflow.runtime.application.runtime.port.WakeUpPublisher;
import dev.ngb.workflow.runtime.domain.timer.Timer;
import java.time.Instant;
import java.util.List;

/**
 * Fires due timers, one transaction per sweep.
 *
 * <p>A due timer is claimed with {@code FOR UPDATE SKIP LOCKED} and marked fired conditionally. Resuming the token is a
 * conditional WAITING -> READY update, so a timer that is swept twice and an event that arrives at the same moment
 * produce one resume; the winning side closes the other's row so nothing can fire it later.
 */
public class TimerSweep {

    private final UnitOfWork unitOfWork;
    private final TimerRepository timers;
    private final TokenRepository tokens;
    private final WaitSubscriptionRepository subscriptions;
    private final WakeUpPublisher wakeUp;

    public TimerSweep(
            UnitOfWork unitOfWork,
            TimerRepository timers,
            TokenRepository tokens,
            WaitSubscriptionRepository subscriptions,
            WakeUpPublisher wakeUp) {
        this.unitOfWork = unitOfWork;
        this.timers = timers;
        this.tokens = tokens;
        this.subscriptions = subscriptions;
        this.wakeUp = wakeUp;
    }

    /** Fires what is due, and answers how many tokens it resumed. */
    public int fireDue(Instant now, int limit) {
        return unitOfWork.transactional(() -> {
            List<Timer> due = timers.lockDue(now, limit);
            int fired = 0;
            for (Timer timer : due) {
                if (!timers.markFired(timer.id(), now)) {
                    continue;
                }
                String branch = branch(timer);
                if (branch == null) {
                    // A kind whose phase has not landed yet: the row is spent, the token stays parked.
                    continue;
                }
                if (!tokens.resume(timer.tokenId(), branch, null, now)) {
                    continue;
                }
                subscriptions.resolveForToken(timer.tokenId(), now);
                timers.cancelForToken(timer.tokenId(), now);
                wakeUp.wake(timer.tenantId());
                fired++;
            }
            return fired;
        });
    }

    private static String branch(Timer timer) {
        return switch (timer.kind()) {
            case DELAY -> "delay";
            case WAIT_TIMEOUT -> "timeout";
            case TASK_DUE, RETRY -> null;
        };
    }
}
