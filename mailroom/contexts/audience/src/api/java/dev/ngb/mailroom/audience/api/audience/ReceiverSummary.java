package dev.ngb.mailroom.audience.api.audience;

import java.util.Map;
import java.util.UUID;

/** One receiver as another context needs it to render a campaign email. */
public record ReceiverSummary(
        UUID id, String email, String firstName, String lastName, Map<String, Object> attributes) {}
