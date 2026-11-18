package dev.ngb.core.audit;

/** Records administrative actions in the caller's transaction. */
public interface AuditLog {

    void record(AuditEntry entry);
}
