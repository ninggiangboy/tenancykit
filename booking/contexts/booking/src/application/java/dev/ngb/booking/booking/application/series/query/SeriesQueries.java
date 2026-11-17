package dev.ngb.booking.booking.application.series.query;

import dev.ngb.core.error.NotFoundException;
import dev.ngb.core.runtime.UnitOfWork;
import java.util.List;
import java.util.UUID;

/** Reads the current tenant's series for the GET handlers. */
public class SeriesQueries {

    private final UnitOfWork unitOfWork;
    private final SeriesReadRepository series;

    public SeriesQueries(UnitOfWork unitOfWork, SeriesReadRepository series) {
        this.unitOfWork = unitOfWork;
        this.series = series;
    }

    public List<SeriesView> list() {
        return unitOfWork.transactional(series::list);
    }

    /** @throws NotFoundException when no series of this tenant has the id */
    public SeriesView get(UUID seriesId) {
        return unitOfWork.transactional(() -> series
                .findById(seriesId)
                .orElseThrow(() -> new NotFoundException("No series exists here with that id.")));
    }
}
