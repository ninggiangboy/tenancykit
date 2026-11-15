package dev.ngb.email.sending.application.tracking.port;

import dev.ngb.email.sending.domain.tracking.EmailLink;
import java.util.Optional;
import java.util.UUID;

/** The recorded click targets. It is written at render time and purged with the body. */
public interface EmailLinkRepository {

    void insert(EmailLink link);

    Optional<String> findUrl(UUID emailId, int position);

    /** Removes the links whose email body has already been purged, at most {@code limit} rows. */
    int purgeForPurgedBodies(java.time.Instant bodyCutoff, int limit);
}
