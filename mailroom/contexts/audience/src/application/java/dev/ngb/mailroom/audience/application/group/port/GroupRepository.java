package dev.ngb.mailroom.audience.application.group.port;

import dev.ngb.mailroom.audience.domain.group.ReceiverGroup;
import java.util.Optional;
import java.util.UUID;

/** The write side of a tenant's static groups. */
public interface GroupRepository {

    void insert(ReceiverGroup group);

    boolean update(ReceiverGroup group, long expectedVersion);

    boolean delete(UUID groupId);

    Optional<ReceiverGroup> findById(UUID groupId);
}
