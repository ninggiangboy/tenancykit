package dev.ngb.platform.boot.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.ngb.platform.Application;
import dev.ngb.platform.support.PlatformContainerConfig;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.json.JsonMapper;

/** The token endpoint: a right secret gets a ten-minute suite token, a wrong one gets 401 with no detail. */
@SpringBootTest(classes = Application.class)
@AutoConfigureMockMvc
@Import(PlatformContainerConfig.class)
class TokenEndpointIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired SuiteTokenIssuer issuer;

    private final JsonMapper json = JsonMapper.builder().build();

    @Test
    void aServiceClientGetsATokenForItsAudience() throws Exception {
        MvcResult result = mvc.perform(post("/oauth/token")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("grant_type", "client_credentials")
                        .param("audience", "platform")
                        .header("Authorization", basic(PlatformContainerConfig.CLIENT_ID, PlatformContainerConfig.CLIENT_SECRET)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token_type").value("Bearer"))
                .andExpect(jsonPath("$.expires_in").value(600))
                .andReturn();

        String token = json.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .get("access_token")
                .asString();
        var jwt = issuer.decoder().decode(token);
        assertThat(jwt.getAudience()).containsExactly("platform");
        assertThat(jwt.getClaimAsString("scope")).isEqualTo("platform:replica:read");
        assertThat(jwt.getClaimAsString("jti")).isNotBlank();
    }

    @Test
    void aWrongSecretAnswers401WithoutSayingWhy() throws Exception {
        mvc.perform(post("/oauth/token")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("grant_type", "client_credentials")
                        .param("audience", "platform")
                        .header("Authorization", basic(PlatformContainerConfig.CLIENT_ID, "not-the-secret")))
                .andExpect(status().isUnauthorized());
    }

    /**
     * The audiences a token may name are the apps the deployment ships, one per permission catalog: a catalog added to
     * the build becomes mintable, and a name nobody ships is refused before any credential is read.
     */
    @Test
    void theAudiencesAreTheShippedApps() throws Exception {
        for (String app : new String[] {"platform", "billing", "crm", "loyalty"}) {
            mvc.perform(post("/oauth/token")
                            .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                            .param("grant_type", "client_credentials")
                            .param("audience", app)
                            .header("Authorization", basic(
                                    PlatformContainerConfig.CLIENT_ID, PlatformContainerConfig.CLIENT_SECRET)))
                    .andExpect(status().isOk());
        }
        mvc.perform(post("/oauth/token")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("grant_type", "client_credentials")
                        .param("audience", "no-such-app")
                        .header("Authorization", basic(
                                PlatformContainerConfig.CLIENT_ID, PlatformContainerConfig.CLIENT_SECRET)))
                .andExpect(status().isBadRequest());
    }

    private static String basic(String id, String secret) {
        return "Basic " + Base64.getEncoder().encodeToString((id + ":" + secret).getBytes(StandardCharsets.UTF_8));
    }
}
