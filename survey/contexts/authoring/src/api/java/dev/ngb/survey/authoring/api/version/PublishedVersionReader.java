package dev.ngb.survey.authoring.api.version;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * The published version a respondent's browser reads, as {@code collection} asks for it.
 *
 * <p>A visit pins the version it opened, so it asks for that number rather than "the current one" and a publish never
 * changes the questions under a respondent who is already answering.
 */
public interface PublishedVersionReader {

    /**
     * One published version of a survey.
     *
     * @param id the version row's id, which a response records
     * @param versionNo the number the document was published under
     * @param document the version document itself, which is the contract a fill UI evaluates
     */
    record PublishedVersion(UUID id, int versionNo, Map<String, Object> document) {}

    /** The survey's current version, which is what a new visit pins, or empty when it was never published. */
    Optional<PublishedVersion> current(UUID surveyId);

    /** One version by number, or empty when the survey has no such version. */
    Optional<PublishedVersion> byNumber(UUID surveyId, int versionNo);
}
