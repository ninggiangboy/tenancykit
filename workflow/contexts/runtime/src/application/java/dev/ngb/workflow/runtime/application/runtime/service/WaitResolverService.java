package dev.ngb.workflow.runtime.application.runtime.service;

import dev.ngb.core.runtime.UnitOfWork;
import dev.ngb.workflow.runtime.api.wait.WaitResolver;
import dev.ngb.workflow.runtime.application.runtime.port.TimerRepository;
import dev.ngb.workflow.runtime.application.runtime.port.TokenRepository;
import dev.ngb.workflow.runtime.application.runtime.port.WaitSubscriptionRepository;
import dev.ngb.workflow.runtime.application.runtime.port.WakeUpPublisher;
import dev.ngb.workflow.runtime.domain.wait.WaitSubscription;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Resumes the waits an arriving event satisfies, inside the caller's transaction.
 *
 * <p>The token update is the race: whichever of the event and the timer changes WAITING to READY first wins, and the
 * loser updates nothing. The winner closes the subscription and the token's remaining timers, so the same wait can never
 * take two branches.
 */
public class WaitResolverService implements WaitResolver {

    private final UnitOfWork unitOfWork;
    private final WaitSubscriptionRepository subscriptions;
    private final TokenRepository tokens;
    private final TimerRepository timers;
    private final WakeUpPublisher wakeUp;

    public WaitResolverService(
            UnitOfWork unitOfWork,
            WaitSubscriptionRepository subscriptions,
            TokenRepository tokens,
            TimerRepository timers,
            WakeUpPublisher wakeUp) {
        this.unitOfWork = unitOfWork;
        this.subscriptions = subscriptions;
        this.tokens = tokens;
        this.timers = timers;
        this.wakeUp = wakeUp;
    }

    @Override
    public int resolve(String source, String eventType, Object document, Instant receivedAt) {
        Object data = document instanceof Map<?, ?> envelope ? envelope.get("data") : document;
        return unitOfWork.transactional(() -> {
            List<WaitSubscription> waiting = subscriptions.matching(source, eventType, receivedAt);
            int resumed = 0;
            for (WaitSubscription subscription : waiting) {
                if (!tokens.resume(subscription.tokenId(), "event", data, receivedAt)) {
                    continue;
                }
                subscriptions.resolve(subscription.id(), receivedAt);
                timers.cancelForToken(subscription.tokenId(), receivedAt);
                wakeUp.wake(subscription.tenantId());
                resumed++;
            }
            return resumed;
        });
    }
}
