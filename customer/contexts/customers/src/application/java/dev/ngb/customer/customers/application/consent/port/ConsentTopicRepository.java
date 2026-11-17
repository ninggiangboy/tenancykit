package dev.ngb.customer.customers.application.consent.port;

import dev.ngb.customer.customers.domain.consent.ConsentTopic;
import java.util.Optional;

/** How the write side stores consent topics. The Tenant's key is the identity of a topic. */
public interface ConsentTopicRepository {

    /**
     * Inserts a topic the caller believes does not exist yet.
     *
     * @return false when another caller stored the same key first, which the caller answers with a conflict
     */
    boolean insertIfAbsent(ConsentTopic topic);

    /** Stores the current state of a topic the caller holds a lock on. */
    void update(ConsentTopic topic);

    /** The topic with its row locked until the transaction ends, so two patches of one key apply in turn. */
    Optional<ConsentTopic> lock(String key);

    Optional<ConsentTopic> findByKey(String key);
}
