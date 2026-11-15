package dev.ngb.email.audience.application.receiver.port;

import dev.ngb.email.audience.domain.receiver.Receiver;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** The write side of a tenant's receivers. */
public interface ReceiverRepository {

    Optional<Receiver> findById(UUID receiverId);

    Optional<Receiver> findByAddress(String normalizedAddress);

    /** Inserts or updates by the tenant-scoped normalized address and returns the stored row. */
    Receiver save(Receiver receiver);

    boolean update(Receiver receiver, long expectedVersion);

    boolean delete(UUID receiverId);

    /** Which of {@code ids} belong to the current tenant; used to refuse a cross-tenant membership. */
    List<UUID> existingIds(List<UUID> ids);
}
