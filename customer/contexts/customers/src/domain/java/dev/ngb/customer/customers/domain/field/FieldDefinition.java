package dev.ngb.customer.customers.domain.field;

import dev.ngb.core.id.TenantId;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * One attribute a Tenant defines for its own customers: a key it writes values under, the label it shows, and the type
 * those values must have.
 *
 * <p>The key and the type never change after the definition exists, so a stored value cannot silently start meaning
 * something else. What may change is the label, the choices a {@code CHOICE} accepts — added, never removed — and
 * whether the definition is archived.
 */
public final class FieldDefinition {

    private final TenantId tenantId;
    private final String key;
    private final FieldType type;
    private final Instant createdAt;
    private String label;
    private List<String> choices;
    private boolean archived;

    private FieldDefinition(
            TenantId tenantId,
            String key,
            String label,
            FieldType type,
            List<String> choices,
            boolean archived,
            Instant createdAt) {
        this.tenantId = Objects.requireNonNull(tenantId, "tenantId");
        this.key = Objects.requireNonNull(key, "key");
        this.label = Objects.requireNonNull(label, "label");
        this.type = Objects.requireNonNull(type, "type");
        this.choices = List.copyOf(choices);
        this.archived = archived;
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
    }

    /** A definition that did not exist until now; it accepts values at once. */
    public static FieldDefinition define(
            TenantId tenantId, String key, String label, FieldType type, List<String> choices, Instant now) {
        return new FieldDefinition(tenantId, key, label, type, choices, false, now);
    }

    /** The definition a repository reads back from storage. */
    public static FieldDefinition restore(
            TenantId tenantId,
            String key,
            String label,
            FieldType type,
            List<String> choices,
            boolean archived,
            Instant createdAt) {
        return new FieldDefinition(tenantId, key, label, type, choices, archived, createdAt);
    }

    /**
     * Changes the label, adds choices, or archives and unarchives the definition. A null argument leaves that property
     * as it is, which is what a patch that carries only one of them means.
     *
     * @return whether anything changed, so an identical patch writes nothing
     */
    public boolean change(String newLabel, List<String> addedChoices, Boolean newArchived) {
        boolean changed = false;
        if (newLabel != null && !newLabel.equals(label)) {
            this.label = newLabel;
            changed = true;
        }
        if (addedChoices != null && !addedChoices.isEmpty()) {
            List<String> merged = new ArrayList<>(choices);
            merged.addAll(addedChoices);
            this.choices = List.copyOf(merged);
            changed = true;
        }
        if (newArchived != null && newArchived != archived) {
            this.archived = newArchived;
            changed = true;
        }
        return changed;
    }

    /** Whether the definition takes values: an archived one is still read back and takes nothing new. */
    public boolean acceptsValues() {
        return !archived;
    }

    public TenantId tenantId() {
        return tenantId;
    }

    public String key() {
        return key;
    }

    public String label() {
        return label;
    }

    public FieldType type() {
        return type;
    }

    /** The values a {@code CHOICE} accepts, in the order they were added; empty for every other type. */
    public List<String> choices() {
        return choices;
    }

    public boolean archived() {
        return archived;
    }

    public Instant createdAt() {
        return createdAt;
    }
}
