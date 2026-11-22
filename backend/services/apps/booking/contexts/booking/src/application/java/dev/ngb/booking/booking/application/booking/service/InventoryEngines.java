package dev.ngb.booking.booking.application.booking.service;

import dev.ngb.booking.inventory.api.engine.InventoryEngine;
import java.util.List;
import java.util.Locale;

/**
 * The engine a booking claims through, chosen by the kind of offering it sells.
 *
 * <p>The engines are injected by the wiring and asked for the promise they make rather than for their class, so this
 * class names no adapter and a new engine is added by wiring it. A promise that no engine makes is a wiring mistake,
 * not a request failure, so it is an {@link IllegalStateException}: the schema constrains an offering to the two kinds
 * this service knows.
 */
public class InventoryEngines {

    private final List<InventoryEngine> engines;

    public InventoryEngines(List<InventoryEngine> engines) {
        this.engines = List.copyOf(engines);
    }

    /** @throws IllegalStateException when no injected engine promises what this offering kind needs */
    public InventoryEngine of(String offeringKind) {
        InventoryEngine.Capability wanted = capability(offeringKind);
        return engines.stream()
                .filter(engine -> engine.capability() == wanted)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("no inventory engine promises " + wanted));
    }

    private static InventoryEngine.Capability capability(String offeringKind) {
        return switch (offeringKind == null ? "" : offeringKind.toUpperCase(Locale.ROOT)) {
            case "CAPACITY" -> InventoryEngine.Capability.CAPACITY_FLASH_SALE;
            case "INTERVAL" -> InventoryEngine.Capability.INTERVAL_NO_OVERLAP;
            default -> throw new IllegalStateException("an offering kind is INTERVAL or CAPACITY, not " + offeringKind);
        };
    }
}
