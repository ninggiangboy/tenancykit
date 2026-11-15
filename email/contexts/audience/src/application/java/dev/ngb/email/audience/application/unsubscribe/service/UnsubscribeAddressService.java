package dev.ngb.email.audience.application.unsubscribe.service;

import dev.ngb.core.id.TenantId;
import dev.ngb.core.runtime.Clock;
import dev.ngb.core.runtime.UnitOfWork;
import dev.ngb.email.audience.api.unsubscribe.UnsubscribeAddress;
import dev.ngb.email.audience.application.receiver.port.ReceiverRepository;
import dev.ngb.email.audience.application.suppression.port.SuppressionRepository;
import dev.ngb.email.audience.domain.receiver.NormalizedAddress;
import dev.ngb.email.audience.domain.receiver.ReceiverStatus;
import dev.ngb.email.audience.domain.suppression.Suppression;
import dev.ngb.email.audience.domain.suppression.SuppressionReason;
import dev.ngb.email.audience.domain.suppression.SuppressionScope;

/**
 * One-click unsubscribe: write a {@code MARKETING} suppression and flip a known receiver to {@code UNSUBSCRIBED}. It
 * is idempotent, and it never lowers an {@code ALL} suppression the Tenant set on purpose.
 */
public class UnsubscribeAddressService implements UnsubscribeAddress {

    private final UnitOfWork unitOfWork;
    private final ReceiverRepository receivers;
    private final SuppressionRepository suppressions;
    private final Clock clock;

    public UnsubscribeAddressService(
            UnitOfWork unitOfWork,
            ReceiverRepository receivers,
            SuppressionRepository suppressions,
            Clock clock) {
        this.unitOfWork = unitOfWork;
        this.receivers = receivers;
        this.suppressions = suppressions;
        this.clock = clock;
    }

    @Override
    public void unsubscribe(TenantId tenantId, String address) {
        String normalized = NormalizedAddress.normalize(address);
        unitOfWork.transactional(() -> {
            suppressions.save(new Suppression(
                    tenantId, normalized, SuppressionScope.MARKETING, SuppressionReason.UNSUBSCRIBED, clock.now()));
            receivers.findByAddress(normalized).ifPresent(receiver -> {
                if (receiver.status() != ReceiverStatus.UNSUBSCRIBED) {
                    receivers.update(receiver.unsubscribe(clock.now()), receiver.version());
                }
            });
            return null;
        });
    }
}
