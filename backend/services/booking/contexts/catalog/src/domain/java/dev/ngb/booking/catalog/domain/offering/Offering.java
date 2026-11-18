package dev.ngb.booking.catalog.domain.offering;

import dev.ngb.core.id.TenantId;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * What a Tenant sells: the shape of a bookable item, its requirements and its cancellation thresholds. An
 * {@code INTERVAL} offering claims a time range on one resource per slot; a {@code CAPACITY} offering claims places in a
 * session pool and carries no requirements.
 *
 * <p>The code and the kind are chosen once and are immutable; the rest is what a PATCH changes. The version is the ETag:
 * a change moves the instant and bumps it, a body identical to what is stored changes nothing at all. Archiving is the
 * one status transition that is idempotent, so a retried archive answers what the first call left behind.
 */
public final class Offering {

    private final OfferingId id;
    private final TenantId tenantId;
    private final String code;
    private final OfferingKind kind;
    private String name;
    private OfferingStatus status;
    private int durationMin;
    private Integer durationMax;
    private int durationStep;
    private int slotStep;
    private int bufferBefore;
    private int bufferAfter;
    private int leadTimeSeconds;
    private int horizonDays;
    private Integer holdTtlSeconds;
    private int maxQty;
    private boolean publicShowsRemaining;
    private QueueMode queueMode;
    private int admitPerSecond;
    private int admitWindowSeconds;
    private List<Requirement> requirements;
    private List<CancellationWindow> cancellationWindows;
    private final Instant createdAt;
    private Instant updatedAt;
    private long version;

    private Offering(
            OfferingId id,
            TenantId tenantId,
            String code,
            String name,
            OfferingKind kind,
            OfferingStatus status,
            int durationMin,
            Integer durationMax,
            int durationStep,
            int slotStep,
            int bufferBefore,
            int bufferAfter,
            int leadTimeSeconds,
            int horizonDays,
            Integer holdTtlSeconds,
            int maxQty,
            boolean publicShowsRemaining,
            QueueMode queueMode,
            int admitPerSecond,
            int admitWindowSeconds,
            List<Requirement> requirements,
            List<CancellationWindow> cancellationWindows,
            Instant createdAt,
            Instant updatedAt,
            long version) {
        this.id = Objects.requireNonNull(id, "id");
        this.tenantId = Objects.requireNonNull(tenantId, "tenantId");
        this.code = Objects.requireNonNull(code, "code");
        this.kind = Objects.requireNonNull(kind, "kind");
        this.name = Objects.requireNonNull(name, "name");
        this.status = Objects.requireNonNull(status, "status");
        requireSettings(
                durationMin, durationMax, durationStep, slotStep, bufferBefore, bufferAfter, leadTimeSeconds,
                horizonDays, holdTtlSeconds, maxQty, admitPerSecond, admitWindowSeconds);
        this.durationMin = durationMin;
        this.durationMax = durationMax;
        this.durationStep = durationStep;
        this.slotStep = slotStep;
        this.bufferBefore = bufferBefore;
        this.bufferAfter = bufferAfter;
        this.leadTimeSeconds = leadTimeSeconds;
        this.horizonDays = horizonDays;
        this.holdTtlSeconds = holdTtlSeconds;
        this.maxQty = maxQty;
        this.publicShowsRemaining = publicShowsRemaining;
        this.queueMode = Objects.requireNonNull(queueMode, "queueMode");
        this.admitPerSecond = admitPerSecond;
        this.admitWindowSeconds = admitWindowSeconds;
        this.requirements = orderedRequirements(requirements);
        this.cancellationWindows = orderedWindows(cancellationWindows);
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
        this.updatedAt = Objects.requireNonNull(updatedAt, "updatedAt");
        this.version = version;
    }

    /** An offering that did not exist until now: a draft, with no requirements and no cancellation thresholds. */
    public static Offering create(
            OfferingId id,
            TenantId tenantId,
            String code,
            String name,
            OfferingKind kind,
            int durationMin,
            Integer durationMax,
            int durationStep,
            int slotStep,
            int bufferBefore,
            int bufferAfter,
            int leadTimeSeconds,
            int horizonDays,
            Integer holdTtlSeconds,
            int maxQty,
            boolean publicShowsRemaining,
            QueueMode queueMode,
            int admitPerSecond,
            int admitWindowSeconds,
            Instant now) {
        return new Offering(
                id, tenantId, code, name, kind, OfferingStatus.DRAFT, durationMin, durationMax, durationStep, slotStep,
                bufferBefore, bufferAfter, leadTimeSeconds, horizonDays, holdTtlSeconds, maxQty, publicShowsRemaining,
                queueMode, admitPerSecond, admitWindowSeconds, List.of(), List.of(), now, now, 1);
    }

    /** The offering a repository reads back from storage. */
    public static Offering restore(
            OfferingId id,
            TenantId tenantId,
            String code,
            String name,
            OfferingKind kind,
            OfferingStatus status,
            int durationMin,
            Integer durationMax,
            int durationStep,
            int slotStep,
            int bufferBefore,
            int bufferAfter,
            int leadTimeSeconds,
            int horizonDays,
            Integer holdTtlSeconds,
            int maxQty,
            boolean publicShowsRemaining,
            QueueMode queueMode,
            int admitPerSecond,
            int admitWindowSeconds,
            List<Requirement> requirements,
            List<CancellationWindow> cancellationWindows,
            Instant createdAt,
            Instant updatedAt,
            long version) {
        return new Offering(
                id, tenantId, code, name, kind, status, durationMin, durationMax, durationStep, slotStep, bufferBefore,
                bufferAfter, leadTimeSeconds, horizonDays, holdTtlSeconds, maxQty, publicShowsRemaining, queueMode,
                admitPerSecond, admitWindowSeconds, requirements, cancellationWindows, createdAt, updatedAt, version);
    }

    /**
     * Replaces the settings with the values the caller resolved; a PATCH leaves a property alone by resolving it to
     * what is stored, so an identical body changes nothing and bumps no version.
     */
    public boolean change(
            OfferingStatus newStatus,
            String newName,
            int newDurationMin,
            Integer newDurationMax,
            int newDurationStep,
            int newSlotStep,
            int newBufferBefore,
            int newBufferAfter,
            int newLeadTimeSeconds,
            int newHorizonDays,
            Integer newHoldTtlSeconds,
            int newMaxQty,
            boolean newPublicShowsRemaining,
            QueueMode newQueueMode,
            int newAdmitPerSecond,
            int newAdmitWindowSeconds,
            Instant now) {
        Objects.requireNonNull(newStatus, "status");
        Objects.requireNonNull(newName, "name");
        Objects.requireNonNull(newQueueMode, "queueMode");
        requireSettings(
                newDurationMin, newDurationMax, newDurationStep, newSlotStep, newBufferBefore, newBufferAfter,
                newLeadTimeSeconds, newHorizonDays, newHoldTtlSeconds, newMaxQty, newAdmitPerSecond,
                newAdmitWindowSeconds);
        if (status == newStatus
                && name.equals(newName)
                && durationMin == newDurationMin
                && Objects.equals(durationMax, newDurationMax)
                && durationStep == newDurationStep
                && slotStep == newSlotStep
                && bufferBefore == newBufferBefore
                && bufferAfter == newBufferAfter
                && leadTimeSeconds == newLeadTimeSeconds
                && horizonDays == newHorizonDays
                && Objects.equals(holdTtlSeconds, newHoldTtlSeconds)
                && maxQty == newMaxQty
                && publicShowsRemaining == newPublicShowsRemaining
                && queueMode == newQueueMode
                && admitPerSecond == newAdmitPerSecond
                && admitWindowSeconds == newAdmitWindowSeconds) {
            return false;
        }
        this.status = newStatus;
        this.name = newName;
        this.durationMin = newDurationMin;
        this.durationMax = newDurationMax;
        this.durationStep = newDurationStep;
        this.slotStep = newSlotStep;
        this.bufferBefore = newBufferBefore;
        this.bufferAfter = newBufferAfter;
        this.leadTimeSeconds = newLeadTimeSeconds;
        this.horizonDays = newHorizonDays;
        this.holdTtlSeconds = newHoldTtlSeconds;
        this.maxQty = newMaxQty;
        this.publicShowsRemaining = newPublicShowsRemaining;
        this.queueMode = newQueueMode;
        this.admitPerSecond = newAdmitPerSecond;
        this.admitWindowSeconds = newAdmitWindowSeconds;
        this.updatedAt = now;
        this.version++;
        return true;
    }

    /** Moves the offering to {@code ARCHIVED}; an offering already archived is left exactly as it is. */
    public boolean archive(Instant now) {
        if (status == OfferingStatus.ARCHIVED) {
            return false;
        }
        this.status = OfferingStatus.ARCHIVED;
        this.updatedAt = now;
        this.version++;
        return true;
    }

    /** Replaces every requirement; a set identical to what is stored changes nothing and bumps no version. */
    public boolean replaceRequirements(List<Requirement> newRequirements, Instant now) {
        List<Requirement> stored = orderedRequirements(newRequirements);
        if (requirements.equals(stored)) {
            return false;
        }
        this.requirements = stored;
        this.updatedAt = now;
        this.version++;
        return true;
    }

    /** Replaces every cancellation threshold; a set identical to what is stored changes nothing and bumps no version. */
    public boolean replaceCancellationWindows(List<CancellationWindow> newWindows, Instant now) {
        List<CancellationWindow> stored = orderedWindows(newWindows);
        if (cancellationWindows.equals(stored)) {
            return false;
        }
        this.cancellationWindows = stored;
        this.updatedAt = now;
        this.version++;
        return true;
    }

    public OfferingId id() {
        return id;
    }

    public TenantId tenantId() {
        return tenantId;
    }

    public String code() {
        return code;
    }

    public String name() {
        return name;
    }

    public OfferingKind kind() {
        return kind;
    }

    public OfferingStatus status() {
        return status;
    }

    public int durationMin() {
        return durationMin;
    }

    /** The longest duration a booking may take; null means the duration is fixed at {@link #durationMin()}. */
    public Integer durationMax() {
        return durationMax;
    }

    public int durationStep() {
        return durationStep;
    }

    public int slotStep() {
        return slotStep;
    }

    public int bufferBefore() {
        return bufferBefore;
    }

    public int bufferAfter() {
        return bufferAfter;
    }

    public int leadTimeSeconds() {
        return leadTimeSeconds;
    }

    public int horizonDays() {
        return horizonDays;
    }

    /** How long a hold lives; null leaves the housekeeping default in charge. */
    public Integer holdTtlSeconds() {
        return holdTtlSeconds;
    }

    public int maxQty() {
        return maxQty;
    }

    public boolean publicShowsRemaining() {
        return publicShowsRemaining;
    }

    public QueueMode queueMode() {
        return queueMode;
    }

    public int admitPerSecond() {
        return admitPerSecond;
    }

    public int admitWindowSeconds() {
        return admitWindowSeconds;
    }

    /** The requirements, ordered by slot code so a read and a write agree on the order. */
    public List<Requirement> requirements() {
        return requirements;
    }

    /** The cancellation thresholds, longest notice first. */
    public List<CancellationWindow> cancellationWindows() {
        return cancellationWindows;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant updatedAt() {
        return updatedAt;
    }

    public long version() {
        return version;
    }

    private static List<Requirement> orderedRequirements(List<Requirement> requirements) {
        if (requirements == null || requirements.isEmpty()) {
            return List.of();
        }
        List<Requirement> ordered = new ArrayList<>(requirements);
        ordered.sort(Comparator.comparing(Requirement::slotCode));
        return List.copyOf(ordered);
    }

    private static List<CancellationWindow> orderedWindows(List<CancellationWindow> windows) {
        if (windows == null || windows.isEmpty()) {
            return List.of();
        }
        List<CancellationWindow> ordered = new ArrayList<>(windows);
        ordered.sort(Comparator.comparingInt(CancellationWindow::minutesBeforeStart).reversed());
        return List.copyOf(ordered);
    }

    private static void requireSettings(
            int durationMin,
            Integer durationMax,
            int durationStep,
            int slotStep,
            int bufferBefore,
            int bufferAfter,
            int leadTimeSeconds,
            int horizonDays,
            Integer holdTtlSeconds,
            int maxQty,
            int admitPerSecond,
            int admitWindowSeconds) {
        if (durationMin < 1) {
            throw new IllegalArgumentException("durationMin must be at least 1");
        }
        if (durationMax != null && durationMax < durationMin) {
            throw new IllegalArgumentException("durationMax must be at least durationMin");
        }
        if (durationStep < 1) {
            throw new IllegalArgumentException("durationStep must be at least 1");
        }
        if (slotStep < 1) {
            throw new IllegalArgumentException("slotStep must be at least 1");
        }
        if (bufferBefore < 0 || bufferAfter < 0) {
            throw new IllegalArgumentException("a buffer must be at least 0");
        }
        if (leadTimeSeconds < 0) {
            throw new IllegalArgumentException("leadTimeSeconds must be at least 0");
        }
        if (horizonDays < 1) {
            throw new IllegalArgumentException("horizonDays must be at least 1");
        }
        if (holdTtlSeconds != null && holdTtlSeconds < 1) {
            throw new IllegalArgumentException("holdTtlSeconds must be at least 1");
        }
        if (maxQty < 1) {
            throw new IllegalArgumentException("maxQty must be at least 1");
        }
        if (admitPerSecond < 1) {
            throw new IllegalArgumentException("admitPerSecond must be at least 1");
        }
        if (admitWindowSeconds < 1) {
            throw new IllegalArgumentException("admitWindowSeconds must be at least 1");
        }
    }
}
