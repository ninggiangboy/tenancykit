package dev.ngb.platform.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import dev.ngb.platform.Application;
import dev.ngb.platform.support.OwnerDatabase;
import dev.ngb.platform.support.PlatformContainerConfig;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * Every token request that did not authenticate shares one budget. A caller that invents a fresh client id per guess
 * therefore gets no further than one that hammers the same id, and the anonymous attempts land in a single bucket
 * instead of one per key id.
 */
@SpringBootTest(
        classes = Application.class,
        properties = {
            "platform.security.token-rate-limit.burst=3",
            "platform.security.token-rate-limit.per-second=0"
        })
@AutoConfigureMockMvc
@Import(PlatformContainerConfig.class)
class TokenAnonymousBudgetIntegrationTest {

    private static final int BURST = 3;
    /** A TEST-NET-1 address no other test uses, so the shared caller-address bucket of this test is its own. */
    private static final String CALLER_ADDRESS = "192.0.2.99";

    @Autowired MockMvc mvc;
    @Autowired OwnerDatabase owner;

    @Test
    void guessesWithDifferentClientIdsConsumeOneBudget() throws Exception {
        for (int guess = 0; guess < BURST; guess++) {
            assertThat(status(freshClientId(), "wrong-" + guess))
                    .as("guess %s is inside the burst", guess + 1)
                    .isEqualTo(HttpStatus.UNAUTHORIZED.value());
        }

        assertThat(status(freshClientId(), "wrong-past-the-burst"))
                .as("the guess past the burst is refused although its client id was never seen before")
                .isEqualTo(HttpStatus.TOO_MANY_REQUESTS.value());

        assertThat(owner.count("select count(*) from rate_limit_buckets where bucket_key like 'unauthenticated%'"))
                .as("every unauthenticated guess shares one bucket instead of one bucket per key id")
                .isEqualTo(1);
    }

    private int status(String clientId, String secret) throws Exception {
        return mvc.perform(tokenRequest(clientId, secret)).andReturn().getResponse().getStatus();
    }

    private static MockHttpServletRequestBuilder tokenRequest(String clientId, String secret) {
        return post("/oauth/token")
                .with((RequestPostProcessor) request -> {
                    request.setRemoteAddr(CALLER_ADDRESS);
                    return request;
                })
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .param("grant_type", "client_credentials")
                .param("audience", "billing")
                .header("Authorization", basic(clientId, secret));
    }

    private static String freshClientId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }

    private static String basic(String id, String secret) {
        return "Basic " + Base64.getEncoder().encodeToString((id + ":" + secret).getBytes(StandardCharsets.UTF_8));
    }
}
