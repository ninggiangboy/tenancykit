package dev.ngb.survey.collection.domain.response;

import dev.ngb.core.event.DomainEvent;
import dev.ngb.core.id.TenantId;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * What the collection context announces: a respondent completed a survey.
 *
 * <p>The payload deliberately carries no answers. They are the Tenant's customers' personal data, and a consumer that
 * needs them reads the response through the API with {@code survey:response:read}; an event on a shared broker is not
 * the place for them. The score the server recomputed rides along, because a quiz consumer's whole point is to react
 * to it.
 */
public final class ResponseEvents {

    /** The submitted event's type, which the route set and the consumer's schema both name. */
    public static final String SUBMITTED = "survey-response.submitted";

    private ResponseEvents() {}

    /**
     * One response completed.
     *
     * @param linkId the link the respondent came through, which the payload carries for a consumer that groups by source
     * @param externalRef the personal link's reference, or null for a public link
     * @param respondentIdentity the identity the server resolved, or null when the survey asked for none
     */
    public record ResponseSubmitted(
            UUID eventId,
            TenantId tenantId,
            UUID surveyId,
            UUID versionId,
            UUID responseId,
            UUID linkId,
            String source,
            String externalRef,
            String respondentIdentity,
            java.math.BigDecimal score,
            Instant submittedAt)
            implements DomainEvent {

        @Override
        public String type() {
            return SUBMITTED;
        }

        @Override
        public String aggregate() {
            return "response/" + responseId;
        }

        @Override
        public Instant occurredAt() {
            return submittedAt;
        }

        @Override
        public Map<String, Object> payload() {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("survey_id", surveyId.toString());
            payload.put("version_id", versionId.toString());
            payload.put("response_id", responseId.toString());
            payload.put("link_id", linkId == null ? null : linkId.toString());
            payload.put("source", source);
            payload.put("external_ref", externalRef);
            payload.put("respondent_identity", respondentIdentity);
            payload.put("score", score);
            payload.put("submitted_at", submittedAt.toString());
            return payload;
        }
    }

    /** The response a completed submission produced, as the event reports it. */
    public static ResponseSubmitted submitted(UUID eventId, Response response) {
        return new ResponseSubmitted(
                eventId,
                new TenantId(response.tenantId()),
                response.surveyId(),
                response.versionId(),
                response.id(),
                response.linkId(),
                response.source(),
                response.externalRef(),
                response.respondentIdentity(),
                response.score(),
                response.submittedAt());
    }
}
