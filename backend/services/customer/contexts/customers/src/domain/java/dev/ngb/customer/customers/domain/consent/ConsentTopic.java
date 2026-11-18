package dev.ngb.customer.customers.domain.consent;

import dev.ngb.core.id.TenantId;
import java.util.Objects;

/**
 * One purpose a Tenant's customers consent to. Topics are the Tenant's own, like its fields, because the purposes a
 * business needs consent for are the business's to name.
 *
 * <p>Archiving keeps every record already made and refuses new ones: a withdrawn purpose is not a purpose whose history
 * is thrown away.
 */
public final class ConsentTopic {

    private final TenantId tenantId;
    private final String key;
    private String label;
    private boolean archived;

    private ConsentTopic(TenantId tenantId, String key, String label, boolean archived) {
        this.tenantId = Objects.requireNonNull(tenantId, "tenantId");
        this.key = Objects.requireNonNull(key, "key");
        this.label = Objects.requireNonNull(label, "label");
        this.archived = archived;
    }

    /** A topic that did not exist until now; it takes consent records at once. */
    public static ConsentTopic define(TenantId tenantId, String key, String label) {
        return new ConsentTopic(tenantId, key, label, false);
    }

    /** The topic a repository reads back from storage. */
    public static ConsentTopic restore(TenantId tenantId, String key, String label, boolean archived) {
        return new ConsentTopic(tenantId, key, label, archived);
    }

    /**
     * Changes the label, or archives and unarchives the topic. A null argument leaves that property as it is.
     *
     * @return whether anything changed, so an identical patch writes nothing
     */
    public boolean change(String newLabel, Boolean newArchived) {
        boolean changed = false;
        if (newLabel != null && !newLabel.equals(label)) {
            this.label = newLabel;
            changed = true;
        }
        if (newArchived != null && newArchived != archived) {
            this.archived = newArchived;
            changed = true;
        }
        return changed;
    }

    /** Whether the topic takes records: an archived one keeps its history and accepts nothing new. */
    public boolean acceptsRecords() {
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

    public boolean archived() {
        return archived;
    }
}
