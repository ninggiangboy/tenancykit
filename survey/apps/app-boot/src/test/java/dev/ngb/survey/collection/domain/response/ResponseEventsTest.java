package dev.ngb.survey.collection.domain.response;

import static org.assertj.core.api.Assertions.assertThat;

import dev.ngb.core.id.TenantId;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The response event's contract: it names the response so a consumer can fetch the answers, and carries none itself.
 *
 * <p>Answers are the Tenant's customers' personal data, and a shared broker is not the place for them; the event carries
 * ids, the source, the identity the server resolved and the score it recomputed.
 */
class ResponseEventsTest {

    private static final TenantId TENANT = new TenantId(UUID.randomUUID());
    private static final Instant NOW = Instant.parse("2026-11-16T00:00:00Z");

    @Test
    void theEventCarriesNoAnswers() {
        Response response = completed();

        ResponseEvents.ResponseSubmitted event = ResponseEvents.submitted(UUID.randomUUID(), response);

        assertThat(event.payload()).doesNotContainKeys("answers", "vars");
        assertThat(event.payload())
                .containsEntry("response_id", response.id().toString())
                .containsEntry("source", "qr-store")
                .containsEntry("score", new BigDecimal("4.5"));
    }

    @Test
    void theEventNamesItsTypeAndAggregate() {
        ResponseEvents.ResponseSubmitted event = ResponseEvents.submitted(UUID.randomUUID(), completed());

        assertThat(event.type()).isEqualTo("survey-response.submitted");
        assertThat(event.aggregate()).isEqualTo("response/" + completed().id());
        assertThat(event.occurredAt()).isEqualTo(NOW);
    }

    @Test
    void aPublicLinkCarriesNoRecipientReference() {
        Response response = completed();

        ResponseEvents.ResponseSubmitted event = ResponseEvents.submitted(UUID.randomUUID(), response);

        assertThat(event.payload()).containsEntry("external_ref", "ref-1");
        assertThat(event.payload()).containsEntry("respondent_identity", "ref-1");
    }

    private static Response completed() {
        return Response.draft(
                        UUID.fromString("11111111-1111-7111-8111-111111111111"),
                        TENANT.value(),
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        "en",
                        "qr-store",
                        "ref-1",
                        null,
                        NOW)
                .completed(
                        Map.of("q1", "a"),
                        Map.of(),
                        new BigDecimal("4.5"),
                        "ref-1",
                        "key-1",
                        NOW);
    }
}
