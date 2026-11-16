package dev.ngb.survey.authoring.api.link;

import java.util.Optional;
import java.util.UUID;

/**
 * The one fact {@code collection} needs about a survey before it issues a link: whether the survey is open.
 *
 * <p>It is a narrow seam on purpose. {@code collection} may not read the {@code surveys} table, so anything it needs
 * to decide about a survey comes through this interface, and a future change to what "open" means is one class here
 * rather than a query in another context.
 */
public interface SurveyStateReader {

    /** Whether the survey is open and able to receive a response, or empty when the tenant has no such survey. */
    Optional<Boolean> isOpen(UUID surveyId);
}
