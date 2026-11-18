package dev.ngb.survey.authoring.domain.version;

import dev.ngb.core.event.DomainEvent;
import dev.ngb.core.id.TenantId;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * What the authoring context announces: a version was published, and a survey closed.
 *
 * <p>Each is written to the outbox in the transaction of the change it reports, so an event is never announced for
 * something that rolled back. The payloads are the integration contract — ids, a version number and a timestamp — and
 * never carry a survey's questions, which a consumer reads through the API.
 */
public final class SurveyLifecycleEvents {

    /** The published event's type, which the route set and the consumer's schema both name. */
    public static final String VERSION_PUBLISHED = "survey-version.published";

    /** The closed event's type. */
    public static final String CLOSED = "survey.closed";

    private SurveyLifecycleEvents() {}

    /**
     * A version became the survey's current one.
     *
     * @param surveyId the survey the version belongs to
     * @param versionId the version's own id, which a response records
     * @param versionNo the number it was published under
     */
    public record VersionPublished(
            UUID eventId, TenantId tenantId, UUID surveyId, UUID versionId, int versionNo, Instant occurredAt)
            implements DomainEvent {

        @Override
        public String type() {
            return VERSION_PUBLISHED;
        }

        @Override
        public String aggregate() {
            return "survey/" + surveyId;
        }

        @Override
        public Map<String, Object> payload() {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("survey_id", surveyId.toString());
            payload.put("version_id", versionId.toString());
            payload.put("version_no", versionNo);
            payload.put("published_at", occurredAt.toString());
            return payload;
        }
    }

    /** A survey stopped accepting responses, so a link no longer opens it. */
    public record SurveyClosed(UUID eventId, TenantId tenantId, UUID surveyId, Instant occurredAt)
            implements DomainEvent {

        @Override
        public String type() {
            return CLOSED;
        }

        @Override
        public String aggregate() {
            return "survey/" + surveyId;
        }

        @Override
        public Map<String, Object> payload() {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("survey_id", surveyId.toString());
            payload.put("closed_at", occurredAt.toString());
            return payload;
        }
    }
}
