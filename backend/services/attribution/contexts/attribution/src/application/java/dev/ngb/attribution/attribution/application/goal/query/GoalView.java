package dev.ngb.attribution.attribution.application.goal.query;

import java.math.BigDecimal;
import java.util.UUID;

/** One goal as the API shows it: the key, name, default and status, never a domain aggregate. */
public record GoalView(
        UUID id, String key, String name, BigDecimal defaultValue, String currency, String status, long version) {}
