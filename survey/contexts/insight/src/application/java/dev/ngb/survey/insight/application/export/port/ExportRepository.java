package dev.ngb.survey.insight.application.export.port;

import dev.ngb.survey.insight.domain.export.Export;
import dev.ngb.survey.insight.domain.export.ExportStatus;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** The export ledger: insight's own table, the one place it writes. */
public interface ExportRepository {

    void insert(Export export);

    Optional<Export> findById(UUID exportId);

    /** Moves a queued export to running, so two workers cannot both take it. */
    boolean claim(UUID exportId);

    /** Writes the finished state of an export. */
    void finish(Export export);

    /** Queued exports in arrival order, which is what the worker streams through. */
    List<Export> queued(int limit);
}
