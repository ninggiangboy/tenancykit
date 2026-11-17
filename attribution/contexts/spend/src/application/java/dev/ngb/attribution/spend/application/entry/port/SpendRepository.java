package dev.ngb.attribution.spend.application.entry.port;

import dev.ngb.attribution.spend.domain.entry.SpendEntry;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** The cost store's write side. A manual set upserts the MANUAL row and never touches an imported one. */
public interface SpendRepository {

    /** Inserts or replaces the row with the same campaign, day, source and connection. */
    void upsert(SpendEntry entry);

    /** Inserts or replaces several rows in one batch, one row per campaign, day and source. */
    void upsertAll(List<SpendEntry> entries);

    /**
     * Deletes the MANUAL row for a campaign and day, leaving an imported row untouched.
     *
     * @return how many rows were deleted, 0 or 1
     */
    int deleteManual(UUID campaignId, LocalDate day);
}
