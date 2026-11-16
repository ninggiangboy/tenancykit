package dev.ngb.booking.catalog.application.offering.service;

import dev.ngb.booking.catalog.application.catalog.exception.CatalogValidationException;
import dev.ngb.booking.catalog.application.catalog.exception.InvalidRequirementsException;
import dev.ngb.booking.catalog.application.offering.port.OfferingRepository;
import dev.ngb.booking.catalog.domain.offering.CancellationWindow;
import dev.ngb.booking.catalog.domain.offering.OfferingKind;
import dev.ngb.booking.catalog.domain.offering.OfferingStatus;
import dev.ngb.booking.catalog.domain.offering.QueueMode;
import dev.ngb.booking.catalog.domain.offering.Requirement;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * The rules an offering body must satisfy. A malformed number is a {@code 422 VALIDATION_FAILED}; a requirement set
 * that cannot be answered — one resource named twice, an archived resource, a group that is gone — is a
 * {@code 422 INVALID_REQUIREMENTS}, because the body is well formed and the catalog refuses what it says.
 */
public final class OfferingRules {

    private static final int MAX_REQUIREMENTS = 20;

    private OfferingRules() {}

    /** @return the Tenant's code, trimmed, which is between 1 and 60 characters */
    public static String code(String raw) {
        String code = raw == null ? null : raw.strip();
        if (code == null || code.isEmpty() || code.length() > 60) {
            throw new CatalogValidationException("code must be between 1 and 60 characters");
        }
        return code;
    }

    /** @return the name, trimmed, which is between 1 and 200 characters */
    public static String name(String raw) {
        String name = raw == null ? null : raw.strip();
        if (name == null || name.isEmpty() || name.length() > 200) {
            throw new CatalogValidationException("name must be between 1 and 200 characters");
        }
        return name;
    }

    public static OfferingKind kind(OfferingKind kind) {
        if (kind == null) {
            throw new CatalogValidationException("kind is required");
        }
        return kind;
    }

    public static OfferingStatus status(OfferingStatus status) {
        if (status == null) {
            throw new CatalogValidationException("status is required");
        }
        return status;
    }

    public static QueueMode queueMode(QueueMode mode, QueueMode fallback) {
        return mode == null ? fallback : mode;
    }

    /** @return a number that must be present and at least one */
    public static int requiredPositive(Integer value, String field) {
        if (value == null) {
            throw new CatalogValidationException(field + " is required");
        }
        if (value < 1) {
            throw new CatalogValidationException(field + " must be at least 1");
        }
        return value;
    }

    /** @return the number, or {@code fallback} when the caller sent none, which must be at least one */
    public static int positive(Integer value, int fallback, String field) {
        int resolved = value == null ? fallback : value;
        if (resolved < 1) {
            throw new CatalogValidationException(field + " must be at least 1");
        }
        return resolved;
    }

    /** @return the number, or {@code fallback} when the caller sent none, which must be at least zero */
    public static int nonNegative(Integer value, int fallback, String field) {
        int resolved = value == null ? fallback : value;
        if (resolved < 0) {
            throw new CatalogValidationException(field + " must be at least 0");
        }
        return resolved;
    }

    /** @return the number, or null when the caller sent none, which must be at least one */
    public static Integer nullablePositive(Integer value, String field) {
        if (value != null && value < 1) {
            throw new CatalogValidationException(field + " must be at least 1");
        }
        return value;
    }

    /** @return the longest duration, or null for a fixed duration, which cannot be shorter than the shortest */
    public static Integer durationMax(Integer value, int durationMin) {
        if (value != null && value < durationMin) {
            throw new CatalogValidationException("durationMax must be at least durationMin");
        }
        return value;
    }

    public static boolean flag(Boolean value, boolean fallback) {
        return value == null ? fallback : value;
    }

    /** One requirement as a caller sent it; a slot code is unique among an offering's requirements. */
    public static Requirement requirement(String slotCode, UUID resourceId, UUID groupId) {
        String code = slotCode == null ? null : slotCode.strip();
        if (code == null || code.isEmpty() || code.length() > 60) {
            throw new InvalidRequirementsException("a requirement's slotCode must be between 1 and 60 characters");
        }
        if ((resourceId == null) == (groupId == null)) {
            throw new InvalidRequirementsException("a requirement names exactly one resource or group");
        }
        return new Requirement(code, resourceId, groupId);
    }

    /** One cancellation threshold as a caller sent it; a threshold minute is named at most once. */
    public static CancellationWindow window(Integer minutesBeforeStart, String code) {
        if (minutesBeforeStart == null || minutesBeforeStart < 0) {
            throw new CatalogValidationException("minutesBeforeStart must be at least 0");
        }
        String value = code == null ? null : code.strip();
        if (value == null || value.isEmpty() || value.length() > 60) {
            throw new CatalogValidationException("a cancellation window's code must be between 1 and 60 characters");
        }
        return new CancellationWindow(minutesBeforeStart, value);
    }

    /**
     * The requirements an offering may carry: at most twenty, no slot code twice, no resource named twice, and every
     * named resource or group present in the tenant. A {@code CAPACITY} offering carries none.
     *
     * @return the requirements ordered by slot code, so a write and a read agree on the order
     */
    public static List<Requirement> requirements(
            OfferingKind kind, List<Requirement> raw, OfferingRepository offerings) {
        List<Requirement> requirements = raw == null ? List.of() : List.copyOf(raw);
        if (kind == OfferingKind.CAPACITY && !requirements.isEmpty()) {
            throw new InvalidRequirementsException("a CAPACITY offering carries no requirements");
        }
        if (requirements.size() > MAX_REQUIREMENTS) {
            throw new InvalidRequirementsException("an offering carries at most " + MAX_REQUIREMENTS + " requirements");
        }
        Set<String> slots = new HashSet<>();
        Set<UUID> resources = new HashSet<>();
        Set<UUID> groups = new HashSet<>();
        for (Requirement requirement : requirements) {
            if (!slots.add(requirement.slotCode())) {
                throw new InvalidRequirementsException("the slot " + requirement.slotCode() + " is named twice");
            }
            if (requirement.resourceId() != null && !resources.add(requirement.resourceId())) {
                throw new InvalidRequirementsException("the resource " + requirement.resourceId() + " is named twice");
            }
            if (requirement.groupId() != null) {
                groups.add(requirement.groupId());
            }
        }
        if (!resources.isEmpty() && !offerings.resourcesExistAndAreActive(resources)) {
            throw new InvalidRequirementsException("a requirement names a resource that does not exist here or is archived");
        }
        if (!groups.isEmpty() && !offerings.groupsExist(groups)) {
            throw new InvalidRequirementsException("a requirement names a group that does not exist here");
        }
        List<Requirement> ordered = new ArrayList<>(requirements);
        ordered.sort(Comparator.comparing(Requirement::slotCode));
        return List.copyOf(ordered);
    }

    /** @return the cancellation thresholds, longest notice first, with no threshold minute named twice */
    public static List<CancellationWindow> cancellationWindows(List<CancellationWindow> raw) {
        List<CancellationWindow> windows = raw == null ? List.of() : List.copyOf(raw);
        Set<Integer> minutes = new HashSet<>();
        for (CancellationWindow window : windows) {
            if (!minutes.add(window.minutesBeforeStart())) {
                throw new CatalogValidationException(
                        "the cancellation threshold " + window.minutesBeforeStart() + " is named twice");
            }
        }
        List<CancellationWindow> ordered = new ArrayList<>(windows);
        ordered.sort(Comparator.comparingInt(CancellationWindow::minutesBeforeStart).reversed());
        return List.copyOf(ordered);
    }
}
