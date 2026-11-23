package dev.ngb.workflow.definitions.adapter.definition.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import dev.ngb.workflow.shared.WorkflowPermissions;
import dev.ngb.workflow.support.WorkflowApiTest;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The definition endpoints end to end: create, edit the draft under {@code If-Match}, publish an immutable version,
 * read the versions, retire, and simulate without a side effect.
 */
class DefinitionApiTest extends WorkflowApiTest {

    private static final String MANAGE = WorkflowPermissions.DEFINITION_MANAGE;
    private static final String READ = WorkflowPermissions.DEFINITION_READ;

    private static final String TRIGGER_TO_END = """
            {"graph":{"start":"start","nodes":[
              {"id":"start","type":"trigger","next":"done"},
              {"id":"done","type":"end"}]}}
            """;

    private static final String NO_END = """
            {"graph":{"start":"start","nodes":[
              {"id":"start","type":"trigger","next":"done"}]}}
            """;

    private static final String REFUND_BRANCH = """
            {"graph":{"start":"start","nodes":[
              {"id":"start","type":"trigger","next":"choose"},
              {"id":"choose","type":"branch",
               "cases":[{"when":{"op":"eq","args":[{"input":"kind"},"refund"]},"next":"refund"}],
               "else":"other"},
              {"id":"refund","type":"set","values":{"amount":{"input":"amount"}},"next":"refunded"},
              {"id":"refunded","type":"end"},
              {"id":"other","type":"end"}]}}
            """;

    private final JsonMapper json = JsonMapper.builder().build();

    @Test
    void aDefinitionIsCreatedEditedPublishedAndRetired() throws Exception {
        Session session = tenant(MANAGE, READ);
        Created created = create(session, "dunning", "Payment dunning");
        assertThat(created.status).isEqualTo(201);
        assertThat(created.etag).isNotBlank();

        MvcResult draft = putAs(session, "/system/v1/definitions/{id}/draft", TRIGGER_TO_END, created.etag, created.id)
                .andReturn();
        assertThat(draft.getResponse().getStatus()).isEqualTo(200);
        assertThat(read(draft).get("problems").size()).isZero();
        String draftEtag = draft.getResponse().getHeader("ETag");
        assertThat(draftEtag).isNotEqualTo(created.etag);

        MvcResult first = postAs(session, "/system/v1/definitions/{id}/publish", null, created.id)
                .andReturn();
        assertThat(first.getResponse().getStatus()).isEqualTo(428);

        MvcResult published = send(
                        post("/system/v1/definitions/{id}/publish", created.id), session, null, draftEtag)
                .andReturn();
        assertThat(published.getResponse().getStatus()).isEqualTo(201);
        assertThat(read(published).get("version").asInt()).isEqualTo(1);
        String publishEtag = published.getResponse().getHeader("ETag");

        MvcResult second = send(
                        post("/system/v1/definitions/{id}/publish", created.id), session, null, publishEtag)
                .andReturn();
        assertThat(second.getResponse().getStatus()).isEqualTo(201);
        assertThat(read(second).get("version").asInt()).isEqualTo(2);

        JsonNode versions = read(getAs(session, "/system/v1/definitions/{id}/versions", created.id).andReturn());
        assertThat(versions.size()).isEqualTo(2);
        assertThat(versions.get(0).get("version").asInt()).isEqualTo(2);
        assertThat(versions.get(1).get("graph").get("start").asString()).isEqualTo("start");

        MvcResult retired = send(
                        post("/system/v1/definitions/{id}/retire", created.id), session, null,
                        second.getResponse().getHeader("ETag"))
                .andReturn();
        assertThat(retired.getResponse().getStatus()).isEqualTo(200);
        assertThat(read(retired).get("status").asString()).isEqualTo("RETIRED");
    }

    @Test
    void aStaleIfMatchIsRefusedAndCarriesTheCurrentVersion() throws Exception {
        Session session = tenant(MANAGE, READ);
        Created created = create(session, "stale", "Stale draft");
        MvcResult first = putAs(session, "/system/v1/definitions/{id}/draft", TRIGGER_TO_END, created.etag, created.id)
                .andReturn();
        String fresh = first.getResponse().getHeader("ETag");

        MvcResult stale = putAs(session, "/system/v1/definitions/{id}/draft", NO_END, created.etag, created.id)
                .andReturn();

        assertThat(stale.getResponse().getStatus()).isEqualTo(412);
        assertThat(stale.getResponse().getHeader("ETag")).isEqualTo(fresh);
    }

    @Test
    void publishingAnInvalidDraftAnswers422WithPointers() throws Exception {
        Session session = tenant(MANAGE, READ);
        Created created = create(session, "invalid", "Invalid draft");
        MvcResult draft = putAs(session, "/system/v1/definitions/{id}/draft", NO_END, created.etag, created.id)
                .andReturn();
        assertThat(draft.getResponse().getStatus()).isEqualTo(200);
        assertThat(read(draft).get("problems").size()).isGreaterThan(0);

        MvcResult publish = send(
                        post("/system/v1/definitions/{id}/publish", created.id), session, null,
                        draft.getResponse().getHeader("ETag"))
                .andReturn();

        assertThat(publish.getResponse().getStatus()).isEqualTo(422);
        JsonNode problems = read(publish).get("problems");
        assertThat(problems.size()).isGreaterThan(0);
        problems.forEach(problem -> {
            assertThat(problem.get("pointer").asString()).startsWith("/");
            assertThat(problem.get("code").asString()).isNotBlank();
        });
    }

    @Test
    void simulateWalksTheDraftWithoutSideEffects() throws Exception {
        Session session = tenant(MANAGE, READ);
        Created created = create(session, "simulate", "Simulated refund");
        MvcResult draft = putAs(session, "/system/v1/definitions/{id}/draft", REFUND_BRANCH, created.etag, created.id)
                .andReturn();
        assertThat(read(draft).get("problems").size()).isZero();

        JsonNode result = read(postAs(
                        session,
                        "/system/v1/definitions/{id}/simulate",
                        "{\"input\":{\"kind\":\"refund\",\"amount\":12.5}}",
                        created.id)
                .andReturn());

        assertThat(result.get("problems").size()).isZero();
        assertThat(texts(result.get("path"))).containsExactly("start", "choose", "refund", "refunded");
        assertThat(result.get("decisions").get(0).get("caseIndex").asInt()).isEqualTo(0);
        assertThat(result.get("decisions").get(0).get("next").asString()).isEqualTo("refund");
        assertThat(result.get("variables").get("amount").asDouble()).isEqualTo(12.5);
        assertThat(result.get("stoppedAt").asString()).isEqualTo("end");
    }

    @Test
    void aCredentialWithoutTheManageCodeCannotCreateADefinition() throws Exception {
        Session readOnly = tenant(READ);

        MvcResult result = postAs(
                        readOnly, "/system/v1/definitions", "{\"code\":\"readonly\",\"name\":\"Read only\"}")
                .andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(403);
    }

    private record Created(String id, String etag, int status) {}

    private Created create(Session session, String code, String name) throws Exception {
        MvcResult result = postAs(
                        session,
                        "/system/v1/definitions",
                        "{\"code\":\"" + code + "\",\"name\":\"" + name + "\"}")
                .andReturn();
        int status = result.getResponse().getStatus();
        String id = status == 201 ? read(result).get("id").asString() : null;
        return new Created(id, result.getResponse().getHeader("ETag"), status);
    }

    private JsonNode read(MvcResult result) throws Exception {
        return json.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private static List<String> texts(JsonNode array) {
        List<String> values = new java.util.ArrayList<>();
        array.forEach(node -> values.add(node.asString()));
        return values;
    }
}
