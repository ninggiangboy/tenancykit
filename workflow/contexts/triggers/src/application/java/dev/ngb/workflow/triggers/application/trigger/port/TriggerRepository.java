package dev.ngb.workflow.triggers.application.trigger.port;

import dev.ngb.workflow.triggers.domain.trigger.Trigger;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** The trigger rows a hook, a schedule or the management API read and change. */
public interface TriggerRepository {

    void add(Trigger trigger);

    void update(Trigger trigger);

    Optional<Trigger> findById(UUID id);

    Optional<Trigger> lockById(UUID id);

    /** The enabled triggers of one source that accept this event type, in creation order. */
    List<Trigger> enabledForSource(String source, String eventType);
}
