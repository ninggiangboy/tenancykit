package dev.ngb.core.messaging.broker;

import dev.ngb.core.messaging.config.MessagingProperties;
import java.util.Optional;
import java.util.Set;

/**
 * Which event types leave this service over the broker, and to which exchange. A type that is not listed is never
 * exported, so an internal event can stay internal by simply not being configured.
 */
public final class ExportRoutes {

    private final MessagingProperties properties;

    public ExportRoutes(MessagingProperties properties) {
        this.properties = properties;
    }

    public Optional<String> exchangeFor(String type) {
        return Optional.ofNullable(properties.exports().get(type));
    }

    public Set<String> types() {
        return properties.exports().keySet();
    }
}
