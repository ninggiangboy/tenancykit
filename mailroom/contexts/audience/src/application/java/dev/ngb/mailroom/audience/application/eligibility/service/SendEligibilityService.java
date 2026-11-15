package dev.ngb.mailroom.audience.application.eligibility.service;

import dev.ngb.core.id.TenantId;
import dev.ngb.core.runtime.UnitOfWork;
import dev.ngb.mailroom.audience.api.eligibility.SendEligibility;
import dev.ngb.mailroom.audience.application.receiver.port.ReceiverRepository;
import dev.ngb.mailroom.audience.application.suppression.port.SuppressionRepository;
import dev.ngb.mailroom.audience.domain.receiver.NormalizedAddress;
import dev.ngb.mailroom.audience.domain.receiver.ReceiverStatus;
import dev.ngb.mailroom.audience.domain.suppression.Suppression;
import dev.ngb.mailroom.audience.domain.suppression.SuppressionScope;
import dev.ngb.mailroom.shared.EmailKind;
import java.util.Optional;

/**
 * The eligibility truth table.
 *
 * <ul>
 *   <li>an {@code ALL} suppression stops everything, transactional or marketing;
 *   <li>any suppression, or a receiver that unsubscribed, stops marketing;
 *   <li>a transactional email ignores a marketing suppression, so a password reset still arrives.
 * </ul>
 *
 * It opens a unit of work so it also answers correctly when called outside a caller's transaction; a call from inside
 * one joins it.
 */
public class SendEligibilityService implements SendEligibility {

    private final UnitOfWork unitOfWork;
    private final ReceiverRepository receivers;
    private final SuppressionRepository suppressions;

    public SendEligibilityService(
            UnitOfWork unitOfWork, ReceiverRepository receivers, SuppressionRepository suppressions) {
        this.unitOfWork = unitOfWork;
        this.receivers = receivers;
        this.suppressions = suppressions;
    }

    @Override
    public boolean allows(TenantId tenantId, String address, EmailKind kind) {
        String normalized = NormalizedAddress.normalize(address);
        return unitOfWork.transactional(() -> {
            Optional<Suppression> suppression = suppressions.find(normalized);
            if (suppression.isPresent() && suppression.get().scope() == SuppressionScope.ALL) {
                return false;
            }
            if (!kind.marketing()) {
                return true;
            }
            if (suppression.isPresent()) {
                return false;
            }
            return receivers.findByAddress(normalized)
                    .map(receiver -> receiver.status() == ReceiverStatus.SUBSCRIBED)
                    .orElse(true);
        });
    }
}
