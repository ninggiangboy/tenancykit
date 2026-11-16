package dev.ngb.loyalty.boot.config;

import static org.assertj.core.api.Assertions.assertThat;

import dev.ngb.loyalty.support.PostgresContainerConfig;
import dev.ngb.loyalty.support.RabbitContainerConfig;
import dev.ngb.loyalty.webhook.application.endpoint.service.WebhookSettings;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.dataformat.yaml.YAMLMapper;

/**
 * {@code allowNonPublicTargets} is the one setting that lets a delivery reach an internal address. It is false in the
 * shipped configuration and true only in the compose profile, so a smoke receiver on a loopback address works and a
 * deployment cannot inherit the relaxation.
 */
@SpringBootTest(classes = dev.ngb.loyalty.Application.class)
@ActiveProfiles("api")
class WebhookDestinationPolicyTest {

    private static final YAMLMapper YAML = YAMLMapper.builder().build();

    @Autowired WebhookSettings settings;

    @DynamicPropertySource
    static void infrastructure(DynamicPropertyRegistry registry) {
        PostgresContainerConfig.register(registry);
        RabbitContainerConfig.register(registry);
        // The key is required to start; the destination policy is what this test reads and it is not overridden here.
        registry.add("loyalty.webhooks.master-key", () -> "eHh4eHh4eHh4eHh4eHh4eHh4eHh4eHh4eHh4eHh4eHg=");
    }

    @Test
    void theShippedDefaultRefusesNonPublicTargets() {
        assertThat(settings.allowNonPublicTargets()).isFalse();
    }

    @Test
    void onlyTheComposeProfileTurnsItOn() throws IOException {
        assertThat(valueOf("application.yml")).as("the base configuration must default to false").endsWith(":false}");
        assertThat(valueOf("application-compose.yml")).isEqualTo("true");
        assertThat(List.of("application-api.yml", "application-worker.yml", "application-migrate.yml"))
                .allSatisfy(name -> assertThat(valueOf(name)).as(name + " must not relax the policy").isNull());
    }

    private static String valueOf(String file) throws IOException {
        try (InputStream in = new ClassPathResource(file).getInputStream()) {
            JsonNode root = YAML.readTree(new String(in.readAllBytes(), StandardCharsets.UTF_8));
            JsonNode webhooks = root.path("loyalty").path("webhooks");
            JsonNode value = webhooks.path("allow-non-public-targets");
            return value.isMissingNode() ? null : value.asString();
        }
    }
}
