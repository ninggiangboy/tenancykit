package dev.ngb.platform.tenancy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import dev.ngb.platform.Application;
import dev.ngb.platform.boot.security.SuiteTokenIssuer;
import dev.ngb.platform.support.OwnerDatabase;
import dev.ngb.platform.support.PlatformContainerConfig;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The registry as CI and an operator drive it over HTTP: a registration creates the remote disabled with no active
 * version, repeating it is idempotent and repeating it with other content is a conflict, an entry path outside the
 * published directory is refused, and only the operator's call activates a version.
 */
@SpringBootTest(classes = Application.class)
@AutoConfigureMockMvc
@Import(PlatformContainerConfig.class)
class UiRemoteRegistryIntegrationTest {

    private static final String PLATFORM_HOST = "localhost";
    private static final String INTEGRITY_A = "sha384-" + "A".repeat(64);
    private static final String INTEGRITY_B = "sha384-" + "B".repeat(64);

    private final JsonMapper json = JsonMapper.builder().build();

    @Autowired MockMvc mvc;
    @Autowired SuiteTokenIssuer issuer;
    @Autowired OwnerDatabase owner;

    @Test
    void aFirstRegistrationCreatesTheRemoteDisabledWithNoActiveVersion() throws Exception {
        String name = newName();

        MvcResult created = register(name, "1.0.0+abc1234", INTEGRITY_A);

        assertThat(created.getResponse().getStatus()).isEqualTo(201);
        JsonNode body = json.readTree(created.getResponse().getContentAsString());
        assertThat(body.get("version").asString()).isEqualTo("1.0.0+abc1234");
        assertThat(body.get("registeredBy").asString()).startsWith("service/");

        JsonNode remote = listed(name);
        assertThat(remote.get("enabled").asBoolean()).isFalse();
        assertThat(remote.get("activeVersion").isNull()).isTrue();
        assertThat(strings(remote.get("audiences"))).containsExactly("PLATFORM", "TENANT");
        assertThat(remote.get("versions")).hasSize(1);
    }

    @Test
    void registeringTheSameVersionAgainIsIdempotentAndAnotherContentIsAConflict() throws Exception {
        String name = newName();

        assertThat(register(name, "1.0.0", INTEGRITY_A).getResponse().getStatus()).isEqualTo(201);
        assertThat(register(name, "1.0.0", INTEGRITY_A).getResponse().getStatus()).isEqualTo(200);
        assertThat(register(name, "1.0.0", INTEGRITY_B).getResponse().getStatus()).isEqualTo(409);

        assertThat(owner.count("select count(*) from ui_remote_versions where remote_name = ?", name)).isEqualTo(1);
    }

    @Test
    void anEntryPathOutsideThePublishedDirectoryIsRefused() throws Exception {
        String name = newName();

        MvcResult outside = register(name, "1.0.0", "/console/remotes/other/1.0.0/mf-manifest.json", INTEGRITY_A);

        assertThat(outside.getResponse().getStatus()).isEqualTo(400);
        assertThat(owner.count("select count(*) from ui_remotes where name = ?", name)).isZero();
    }

    @Test
    void theOperatorActivatesAVersionAndEnablesTheRemote() throws Exception {
        String name = newName();
        register(name, "1.0.0", INTEGRITY_A);
        register(name, "2.0.0", INTEGRITY_A);

        MvcResult enabled = setState(name, "{\"enabled\":true,\"activeVersion\":\"2.0.0\"}", "\"1\"");

        assertThat(enabled.getResponse().getStatus()).isEqualTo(200);
        assertThat(enabled.getResponse().getHeader("ETag")).isEqualTo("\"2\"");
        JsonNode body = json.readTree(enabled.getResponse().getContentAsString());
        assertThat(body.get("enabled").asBoolean()).isTrue();
        assertThat(body.get("activeVersion").asString()).isEqualTo("2.0.0");

        MvcResult disabledAgain = setState(name, "{\"enabled\":false}", "\"2\"");
        assertThat(disabledAgain.getResponse().getStatus()).isEqualTo(200);
        assertThat(json.readTree(disabledAgain.getResponse().getContentAsString()).get("enabled").asBoolean()).isFalse();
    }

    @Test
    void anUnregisteredActiveVersionAndAnEnableWithoutOneAreRefused() throws Exception {
        String name = newName();
        register(name, "1.0.0", INTEGRITY_A);

        assertThat(setState(name, "{\"enabled\":true,\"activeVersion\":\"9.9.9\"}", "\"1\"").getResponse().getStatus())
                .isEqualTo(400);
        assertThat(setState(name, "{\"enabled\":true}", "\"1\"").getResponse().getStatus()).isEqualTo(400);
    }

    @Test
    void aStaleIfMatchChangesNothing() throws Exception {
        String name = newName();
        register(name, "1.0.0", INTEGRITY_A);
        long auditsBefore = owner.count("select count(*) from audit_log where subject like 'ui-remote/%'");

        MvcResult stale = setState(name, "{\"enabled\":true,\"activeVersion\":\"1.0.0\"}", "\"9\"");

        assertThat(stale.getResponse().getStatus()).isEqualTo(412);
        assertThat(stale.getResponse().getHeader("ETag")).isEqualTo("\"1\"");
        assertThat(owner.count("select count(*) from audit_log where subject like 'ui-remote/%'")).isEqualTo(auditsBefore);
    }

    @Test
    void anUnknownRemoteIs404() throws Exception {
        assertThat(setState(newName(), "{\"enabled\":true}", null).getResponse().getStatus()).isEqualTo(404);
    }

    private MvcResult register(String name, String version, String integrity) throws Exception {
        return register(name, version, entry(name, version), integrity);
    }

    private MvcResult register(String name, String version, String entryPath, String integrity) throws Exception {
        return mvc.perform(post("/api/v1/ui-remotes/" + name + "/versions")
                        .header("Host", PLATFORM_HOST)
                        .header("Authorization", "Bearer " + adminToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody(name, version, entryPath, integrity, "billing")))
                .andReturn();
    }

    private static String registerBody(String name, String version, String entryPath, String integrity, String appCode) {
        return """
                {"version":"%s","entryPath":"%s","integrity":"%s","sdkMajor":1,"appCode":"%s","title":"%s",
                 "audiences":["PLATFORM","TENANT"],"navOrder":10}"""
                .formatted(version, entryPath, integrity, appCode, name);
    }

    private MvcResult setState(String name, String body, String ifMatch) throws Exception {
        MockHttpServletRequestBuilder request = put("/api/v1/ui-remotes/" + name)
                .header("Host", PLATFORM_HOST)
                .header("Authorization", "Bearer " + adminToken())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body);
        if (ifMatch != null) {
            request.header("If-Match", ifMatch);
        }
        return mvc.perform(request).andReturn();
    }

    private JsonNode listed(String name) throws Exception {
        MvcResult result = mvc.perform(get("/api/v1/ui-remotes")
                        .header("Host", PLATFORM_HOST)
                        .header("Authorization", "Bearer " + adminToken()))
                .andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        for (JsonNode remote : json.readTree(result.getResponse().getContentAsString()).get("data")) {
            if (name.equals(remote.get("name").asString())) {
                return remote;
            }
        }
        throw new AssertionError("the listing does not contain " + name);
    }

    private String adminToken() {
        return issuer.issueService(
                        "test-actor",
                        Set.of("platform:ui-remote:register", "platform:ui-remote:manage", "platform:ui-remote:read"),
                        "platform")
                .token();
    }

    private static List<String> strings(JsonNode array) {
        List<String> values = new ArrayList<>();
        array.forEach(node -> values.add(node.asString()));
        return values;
    }

    private static String newName() {
        return "ui" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
    }

    private static String entry(String name, String version) {
        return "/console/remotes/" + name + "/" + version + "/mf-manifest.json";
    }
}
