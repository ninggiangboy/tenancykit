package dev.ngb.booking.webhook.adapter.delivery.web;

import static dev.ngb.booking_jooq.Tables.BOOKINGS;
import static dev.ngb.booking_jooq.Tables.OUTBOX;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import dev.ngb.booking.booking.application.booking.command.ExpireHoldsUseCase;
import dev.ngb.booking.booking.application.waitlist.command.OfferWaitlistUseCase;
import dev.ngb.booking.shared.BookingPermissions;
import dev.ngb.booking.support.BookingApiTest;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.jooq.JSONB;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The two waitlist facts posted through the use cases that emit them, validated against their committed schemas.
 *
 * <p>The flow is the one a customer sees: a full session, a waiting entry, a cancellation that frees one place, and
 * either the offer or the lapse of the offered hold.
 */
class WaitlistWebhookTest extends BookingApiTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired
    OfferWaitlistUseCase offers;

    @Autowired
    ExpireHoldsUseCase expirations;

    @Test
    void theOfferedPayloadMatchesTheSchema() throws Exception {
        Fixture fixture = fixture();
        postAs(session(fixture), "/system/v1/bookings/{bookingId}/cancel", null, fixture.holder())
                .andExpect(status().isOk());
        assertThat(inTenant(fixture.session().tenant(), offers::execute)).isEqualTo(1);

        assertPayloadMatches(fixture.session(), "waitlist.offered");
    }

    @Test
    void theLapsedPayloadMatchesTheSchema() throws Exception {
        Fixture fixture = fixture();
        postAs(session(fixture), "/system/v1/bookings/{bookingId}/cancel", null, fixture.holder())
                .andExpect(status().isOk());
        inTenant(fixture.session().tenant(), offers::execute);
        String offered = JSON.readTree(getAs(
                                session(fixture), "/system/v1/waitlist/{entryId}", fixture.entry())
                        .andReturn()
                        .getResponse()
                        .getContentAsString(StandardCharsets.UTF_8))
                .get("offeredBookingId")
                .asString();

        inTenant(fixture.session().tenant(), () -> unitOfWork.transactional(() -> dsl.update(BOOKINGS)
                .set(BOOKINGS.HOLD_EXPIRES_AT, Instant.now().minusSeconds(600))
                .where(BOOKINGS.ID.eq(UUID.fromString(offered)))
                .execute()));
        inTenant(fixture.session().tenant(), expirations::execute);

        assertPayloadMatches(fixture.session(), "waitlist.lapsed");
    }

    private Session session(Fixture fixture) {
        return fixture.session();
    }

    private record Fixture(Session session, String holder, String entry) {}

    private Fixture fixture() throws Exception {
        Session session = tenant(
                BookingPermissions.RESOURCE_MANAGE,
                BookingPermissions.OFFERING_MANAGE,
                BookingPermissions.CALENDAR_MANAGE,
                BookingPermissions.BOOKING_HOLD,
                BookingPermissions.WAITLIST_READ,
                BookingPermissions.WAITLIST_MANAGE);
        String code = "wait-ev-" + UUID.randomUUID().toString().substring(0, 8);
        String offering = JSON.readTree(postAs(session, "/system/v1/offerings", """
                        {"code":"%s","name":"Waitlist","kind":"CAPACITY","durationMin":60,"slotStep":60,
                         "horizonDays":30}
                        """.formatted(code))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8))
                .get("id")
                .asString();
        String dtstart = LocalDateTime.now(ZoneOffset.UTC)
                .plusDays(1)
                .withHour(18)
                .withMinute(0)
                .withSecond(0)
                .withNano(0)
                .toString();
        String sessionId = JSON.readTree(postAs(session, "/system/v1/session-series", """
                        {"offeringId":"%s","zone":"UTC","dtstart":"%s","rule":["RRULE:FREQ=DAILY;COUNT=2"],
                         "durationMinutes":60,"capacity":1}
                        """.formatted(offering, dtstart))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8))
                .get("sessions")
                .get(0)
                .get("id")
                .asString();
        String holder = JSON.readTree(postAs(session, "/system/v1/bookings", """
                        {"offeringId":"%s","customerRef":"holder","sessionId":"%s","poolCode":"default","qty":1}
                        """.formatted(offering, sessionId))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8))
                .get("id")
                .asString();
        String entry = JSON.readTree(postAs(session, "/system/v1/waitlist", """
                        {"offeringId":"%s","customerRef":"cust-wait","qty":1,"sessionId":"%s","poolCode":"default"}
                        """.formatted(offering, sessionId))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8))
                .get("id")
                .asString();
        return new Fixture(session, holder, entry);
    }

    private void assertPayloadMatches(Session session, String type) throws Exception {
        JsonNode schema = schema(type);
        JsonNode payload = outboxPayload(session, type);
        assertThat(SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12)
                        .getSchema(schema)
                        .validate(payload))
                .as("the %s payload matches its committed schema", type)
                .isEmpty();
    }

    private JsonNode outboxPayload(Session session, String type) {
        String json = inTenant(
                session.tenant(),
                () -> unitOfWork.transactional(() -> dsl.select(OUTBOX.PAYLOAD)
                        .from(OUTBOX)
                        .where(OUTBOX.TYPE.eq(type))
                        .fetchOptional(OUTBOX.PAYLOAD)
                        .map(JSONB::data)
                        .orElseThrow(() -> new IllegalStateException("no " + type + " row in the outbox"))));
        return JSON.readTree(json);
    }

    private static JsonNode schema(String type) {
        String resource = "/events/" + type + ".schema.json";
        try (InputStream stream = WaitlistWebhookTest.class.getResourceAsStream(resource)) {
            if (stream == null) {
                throw new IllegalStateException(resource + " is not on the classpath");
            }
            return JSON.readTree(new String(stream.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
