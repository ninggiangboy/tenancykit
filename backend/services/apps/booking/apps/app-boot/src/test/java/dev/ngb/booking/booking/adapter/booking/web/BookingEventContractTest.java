package dev.ngb.booking.booking.adapter.booking.web;

import static dev.ngb.booking_jooq.Tables.BOOKINGS;
import static dev.ngb.booking_jooq.Tables.OUTBOX;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import dev.ngb.booking.booking.application.booking.command.ExpireHoldsUseCase;
import dev.ngb.booking.shared.BookingPermissions;
import dev.ngb.booking.support.BookingApiTest;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import org.jooq.JSONB;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The outbox payload of a booking fact carries exactly the fields the committed schema names, and every field the
 * schema requires, checked for the facts the lifecycle announces.
 */
class BookingEventContractTest extends BookingApiTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static final String ALL_DAY_HOURS = """
            {"hours":[{"weekday":1,"opens":"00:00","closes":"24:00"},
                       {"weekday":2,"opens":"00:00","closes":"24:00"},
                       {"weekday":3,"opens":"00:00","closes":"24:00"},
                       {"weekday":4,"opens":"00:00","closes":"24:00"},
                       {"weekday":5,"opens":"00:00","closes":"24:00"},
                       {"weekday":6,"opens":"00:00","closes":"24:00"},
                       {"weekday":7,"opens":"00:00","closes":"24:00"}]}
            """;

    @Autowired
    ExpireHoldsUseCase expirations;

    @Test
    void theHeldPayloadMatchesTheSchema() throws Exception {
        Session session = lifecycle();
        String offering = intervalOffering(session);
        hold(session, offering);

        assertPayloadMatches(session, "booking.held");
    }

    @Test
    void theCancelledPayloadMatchesTheSchema() throws Exception {
        Session session = lifecycle();
        String offering = intervalOffering(session);
        String booking = hold(session, offering);

        postAs(session, "/system/v1/bookings/{bookingId}/cancel", null, booking)
                .andExpect(status().isOk());

        assertPayloadMatches(session, "booking.cancelled");
    }

    @Test
    void theExpiredPayloadMatchesTheSchema() throws Exception {
        Session session = lifecycle();
        String offering = intervalOffering(session);
        String booking = hold(session, offering);
        UUID bookingId = UUID.fromString(booking);

        inTenant(session.tenant(), () -> unitOfWork.transactional(() -> dsl.update(BOOKINGS)
                .set(BOOKINGS.HOLD_EXPIRES_AT, Instant.now().minusSeconds(600))
                .where(BOOKINGS.ID.eq(bookingId))
                .execute()));
        inTenant(session.tenant(), () -> {
            expirations.execute();
        });

        assertPayloadMatches(session, "booking.expired");
    }

    private void assertPayloadMatches(Session session, String type) throws Exception {
        JsonNode schema = schemaNode(type);
        JsonNode payload = outboxPayload(session, type);
        assertThat(names(payload))
                .as("the %s payload carries exactly the schema's properties", type)
                .isEqualTo(names(schema.get("properties")));
        for (JsonNode required : schema.get("required")) {
            assertThat(payload.has(required.asString()))
                    .as("%s requires %s", type, required.asString())
                    .isTrue();
        }
        assertThat(validator(schema).validate(payload))
                .as("the %s payload matches its committed schema", type)
                .isEmpty();
    }

    private static Schema validator(JsonNode schema) {
        return SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12).getSchema(schema);
    }

    private static JsonNode schemaNode(String type) {
        String resource = "/events/" + type + ".schema.json";
        try (InputStream stream = BookingEventContractTest.class.getResourceAsStream(resource)) {
            if (stream == null) {
                throw new IllegalStateException(resource + " is not on the classpath");
            }
            return JSON.readTree(new String(stream.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
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

    private static Set<String> names(JsonNode node) {
        Set<String> names = new TreeSet<>();
        node.propertyNames().forEach(names::add);
        return names;
    }

    private Session lifecycle() {
        return tenant(
                BookingPermissions.RESOURCE_MANAGE,
                BookingPermissions.OFFERING_MANAGE,
                BookingPermissions.BOOKING_READ,
                BookingPermissions.BOOKING_HOLD);
    }

    private String intervalOffering(Session session) throws Exception {
        String code = "contract-" + UUID.randomUUID().toString().substring(0, 8);
        String offering = JSON.readTree(postAs(session, "/system/v1/offerings", """
                        {"code":"%s","name":"Contract","kind":"INTERVAL","durationMin":60,"slotStep":30,
                         "horizonDays":30}
                        """.formatted(code))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8))
                .get("id")
                .asString();
        UUID resource = seedResource(session);
        putAs(session, "/system/v1/offerings/{offeringId}/requirements", """
                {"requirements":[{"slotCode":"staff","resourceId":"%s"}]}
                """.formatted(resource), offering)
                .andExpect(status().isOk());
        return offering;
    }

    private String hold(Session session, String offeringId) throws Exception {
        Instant start = Instant.now()
                .truncatedTo(ChronoUnit.DAYS)
                .plus(Duration.ofDays(1))
                .plus(Duration.ofHours(9));
        return JSON.readTree(postAs(session, "/system/v1/bookings", """
                        {"offeringId":"%s","customerRef":"cust-contract","start":"%s","durationMinutes":60}
                        """.formatted(offeringId, start))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8))
                .get("id")
                .asString();
    }

    private UUID seedResource(Session session) throws Exception {
        String code = "contract-room-" + UUID.randomUUID().toString().substring(0, 8);
        String body = postAs(session, "/system/v1/resources", """
                {"code":"%s","name":"Room %s","zone":"UTC"}
                """.formatted(code, code))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
        UUID resource = UUID.fromString(JSON.readTree(body).get("id").asString());
        putAs(session, "/system/v1/resources/{resourceId}/working-hours", ALL_DAY_HOURS, resource.toString())
                .andExpect(status().isOk());
        return resource;
    }
}
