package dev.ngb.customer.customers.application.customer.port;

import dev.ngb.customer.customers.domain.customer.ErasureRequest;
import java.util.Optional;

/**
 * How the write side keeps the record of an erasure, which is what makes a retried call a replay rather than a second
 * erasure of whatever now answers to that reference.
 *
 * <p>The key is the hash of the reference, never the reference itself. There is no update and no delete: the record is
 * the answer a retry receives, so it has to outlive the request that wrote it.
 */
public interface ErasureRequestRepository {

    /** The erasure this tenant already performed for that reference, if it performed one. */
    Optional<ErasureRequest> findByRefHash(String refHash);

    /**
     * Stores the record of an erasure the caller just performed.
     *
     * @return false when another caller stored the same reference first, which the caller answers by replaying it
     */
    boolean insertIfAbsent(ErasureRequest request);
}
