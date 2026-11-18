package dev.ngb.attribution.spend.application.entry.query;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** The cost store's read side. */
public interface SpendReadRepository {

    /** Every cost row of a campaign in a day range, oldest day first. */
    List<SpendView> list(UUID campaignId, LocalDate fromDay, LocalDate toDayInclusive);
}
