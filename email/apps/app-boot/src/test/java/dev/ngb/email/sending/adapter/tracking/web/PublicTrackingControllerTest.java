package dev.ngb.email.sending.adapter.tracking.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.ngb.email.Application;
import dev.ngb.email.support.PostgresContainerConfig;
import dev.ngb.email.support.RabbitContainerConfig;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The public tracking surface must answer at the path a recipient's mail actually contains in both runtimes.
 *
 * <p>Compose strips {@code /email} at Caddy, so a link arrives as {@code /t/...}; the ALB forwards the path unchanged,
 * so the same link arrives as {@code /email/t/...}. A 404 at either spelling kills every pixel, click and unsubscribe
 * — and with them every rewritten link in a marketing email.
 *
 * <p>The token is deliberately garbage: these endpoints answer the same way for a valid and an invalid token, and
 * that indifference is itself part of the contract.
 */
@SpringBootTest(classes = Application.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@ActiveProfiles("api")
class PublicTrackingControllerTest {

    @Autowired MockMvc mvc;

    @DynamicPropertySource
    static void infrastructure(DynamicPropertyRegistry registry) {
        var postgres = PostgresContainerConfig.postgres();
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", () -> PostgresContainerConfig.APP_ROLE);
        registry.add("spring.datasource.password", () -> PostgresContainerConfig.APP_PASSWORD);
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("spring.flyway.url", postgres::getJdbcUrl);
        registry.add("spring.flyway.user", postgres::getUsername);
        registry.add("spring.flyway.password", postgres::getPassword);
        registry.add("spring.flyway.placeholders.appDbPassword", () -> PostgresContainerConfig.APP_PASSWORD);
        var rabbit = RabbitContainerConfig.rabbit();
        registry.add("spring.rabbitmq.host", rabbit::getHost);
        registry.add("spring.rabbitmq.port", rabbit::getAmqpPort);
        registry.add("spring.rabbitmq.username", rabbit::getAdminUsername);
        registry.add("spring.rabbitmq.password", rabbit::getAdminPassword);
        registry.add("suite.replica.rebuild-on-start", () -> "false");
        registry.add("email.jobs.enabled", () -> "false");
        registry.add("email.tracking.keys.k1", () -> "test-tracking-secret");
    }

    @ParameterizedTest
    @ValueSource(strings = {"/t", "/email/t"})
    void thePixelAnswersWithoutCredentialsAtBothPrefixes(String prefix) throws Exception {
        mvc.perform(get(prefix + "/o/not-a-token.gif"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.IMAGE_GIF));
    }

    @ParameterizedTest
    @ValueSource(strings = {"/t", "/email/t"})
    void anUnusableTokenAnswersTheExpiredPageRatherThanAnError(String prefix) throws Exception {
        mvc.perform(get(prefix + "/c/not-a-token"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Link expired")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"/t", "/email/t"})
    void theUnsubscribePageAnswersWithoutCredentialsAtBothPrefixes(String prefix) throws Exception {
        mvc.perform(get(prefix + "/u/not-a-token"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Unsubscribe")));
    }
}
