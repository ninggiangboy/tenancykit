package dev.ngb.workflow.triggers.adapter.trigger.web;

import static dev.ngb.workflow_jooq.Tables.WORKFLOW_INSTANCE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import dev.ngb.core.runtime.UnitOfWork;
import dev.ngb.workflow.shared.WorkflowPermissions;
import dev.ngb.workflow.support.WorkflowApiTest;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.jooq.DSLContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The three signed and manual ways work starts: a billing hook verifies once and starts one instance, a redelivery is
 * answered as accepted and starts nothing, a tampered or stale delivery is refused, and a repeated manual call is one
 * instance.
 */
class TriggerApiTest extends WorkflowApiTest {

    private static final String MANAGE = WorkflowPermissions.TRIGGER_MANAGE;
    private static final String READ = WorkflowPermissions.DEFINITION_READ;
    private static final String DEFINITION_MANAGE = WorkflowPermissions.DEFINITION_MANAGE;
    private static final String INSTANCE_MANAGE = WorkflowPermissions.INSTANCE_MANAGE;

    private static final String GRAPH = """
            {"graph":{"start":"start","nodes":[
              {"id":"start","type":"trigger","next":"done"},
              {"id":"done","type":"end"}]}}
            """;

    private final JsonMapper json = JsonMapper.builder().build();

    @Autowired
    private DSLContext dsl;

    @Autowired
    private UnitOfWork unitOfWork;

    @Test
    void aSignedHookStartsOneInstanceAndARedeliveryStartsNothing() throws Exception {
        Session session = tenant(MANAGE, READ, DEFINITION_MANAGE, INSTANCE_MANAGE);
        String definitionId = publishedDefinition(session);
        Created created = trigger(session, definitionId, "subscription.payment_failed");
        UUID eventId = UUID.randomUUID();
        String body = event(eventId, "subscription.payment_failed");

        MvcResult first = deliver(session, created, eventId, body, Instant.now().getEpochSecond());
        assertThat(first.getResponse().getStatus()).isEqualTo(200);
        assertThat(read(first).get("started").asInt()).isEqualTo(1);
        assertThat(read(first).get("duplicate").asBoolean()).isFalse();
        assertThat(instances(session)).isEqualTo(1);

        MvcResult again = deliver(session, created, eventId, body, Instant.now().getEpochSecond());
        assertThat(again.getResponse().getStatus()).isEqualTo(200);
        assertThat(read(again).get("duplicate").asBoolean()).isTrue();
        assertThat(instances(session)).isEqualTo(1);
    }

    @Test
    void aTamperedBodyAndAStaleTimestampAreRefusedAlike() throws Exception {
        Session session = tenant(MANAGE, READ, DEFINITION_MANAGE);
        String definitionId = publishedDefinition(session);
        Created created = trigger(session, definitionId, "subscription.payment_failed");
        UUID eventId = UUID.randomUUID();
        String body = event(eventId, "subscription.payment_failed");

        long now = Instant.now().getEpochSecond();
        MvcResult tampered = mvc.perform(post("/hooks/" + created.id())
                        .header("Host", session.host())
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Billing-Event-Id", eventId.toString())
                        .header("Billing-Event-Type", "subscription.payment_failed")
                        .header("Billing-Timestamp", String.valueOf(now))
                        .header("Billing-Signature", signature(created.secret(), now, body))
                        .content(body.replace("\"amount\":1", "\"amount\":999")))
                .andReturn();
        assertThat(tampered.getResponse().getStatus()).isEqualTo(401);

        long stale = Instant.now().minusSeconds(3600).getEpochSecond();
        MvcResult old = deliver(session, created, eventId, body, stale);
        assertThat(old.getResponse().getStatus()).isEqualTo(401);
        assertThat(instances(session)).isZero();
    }

    @Test
    void aRepeatedManualCallIsOneInstanceAndAnotherBodyIsAConflict() throws Exception {
        Session session = tenant(MANAGE, READ, DEFINITION_MANAGE, INSTANCE_MANAGE);
        String definitionId = publishedDefinition(session);
        String firstBody = "{\"definition\":\"" + definitionId + "\",\"input\":{\"n\":1}}";

        MvcResult first = send(post("/system/v1/instances").header("Idempotency-Key", "manual-1"), session,
                        firstBody, null)
                .andReturn();
        assertThat(first.getResponse().getStatus()).isEqualTo(201);
        String firstId = read(first).get("id").asString();

        MvcResult again = send(post("/system/v1/instances").header("Idempotency-Key", "manual-1"), session,
                        firstBody, null)
                .andReturn();
        assertThat(again.getResponse().getStatus()).isEqualTo(201);
        assertThat(read(again).get("id").asString()).isEqualTo(firstId);

        MvcResult other = send(post("/system/v1/instances").header("Idempotency-Key", "manual-1"), session,
                        "{\"definition\":\"" + definitionId + "\",\"input\":{\"n\":2}}", null)
                .andReturn();
        assertThat(other.getResponse().getStatus()).isEqualTo(409);
        assertThat(instances(session)).isEqualTo(1);
    }

    @Test
    void aCredentialWithoutTheManageCodeCannotListTriggers() throws Exception {
        Session readOnly = tenant(READ);
        MvcResult result = getAs(readOnly, "/system/v1/triggers").andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(403);
    }

    // ------------------------------------------------------------------ helpers

    private String publishedDefinition(Session session) throws Exception {
        MvcResult created = postAs(session, "/system/v1/definitions",
                        "{\"code\":\"hook" + UUID.randomUUID().toString().replace("-", "").substring(0, 8)
                                + "\",\"name\":\"Hook target\"}")
                .andReturn();
        String id = read(created).get("id").asString();
        MvcResult draft = putAs(session, "/system/v1/definitions/{id}/draft", GRAPH,
                        created.getResponse().getHeader("ETag"), id)
                .andReturn();
        assertThat(read(draft).get("problems").size()).isZero();
        MvcResult publish = send(post("/system/v1/definitions/{id}/publish", id), session, null,
                        draft.getResponse().getHeader("ETag"))
                .andReturn();
        assertThat(publish.getResponse().getStatus()).isEqualTo(201);
        return id;
    }

    private record Created(String id, String secret) {}

    private Created trigger(Session session, String definitionId, String eventType) throws Exception {
        MvcResult result = postAs(session, "/system/v1/triggers", """
                {"name":"Billing hook","kind":"APP","source":"billing","eventType":"%s","definitionId":"%s"}
                """.formatted(eventType, definitionId)).andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(201);
        JsonNode body = read(result);
        return new Created(body.get("trigger").get("id").asString(), body.get("signingSecret").asString());
    }

    private MvcResult deliver(Session session, Created trigger, UUID eventId, String body, long timestamp)
            throws Exception {
        return mvc.perform(post("/hooks/" + trigger.id())
                        .header("Host", session.host())
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Billing-Event-Id", eventId.toString())
                        .header("Billing-Event-Type", "subscription.payment_failed")
                        .header("Billing-Timestamp", String.valueOf(timestamp))
                        .header("Billing-Signature", signature(trigger.secret(), timestamp, body))
                        .content(body))
                .andReturn();
    }

    private static String event(UUID id, String type) {
        return "{\"id\":\"" + id + "\",\"type\":\"" + type + "\",\"data\":{\"amount\":1}}";
    }

    private static String signature(String secret, long timestamp, String body) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        byte[] digest = mac.doFinal((timestamp + "." + body).getBytes(StandardCharsets.UTF_8));
        return "v1=" + java.util.HexFormat.of().formatHex(digest);
    }

    private long instances(Session session) {
        return inTenant(session.tenant(), () -> unitOfWork.transactional(() -> (long) dsl.fetchCount(WORKFLOW_INSTANCE)));
    }

    private JsonNode read(MvcResult result) throws Exception {
        return json.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }
}
