package dev.ngb.loyalty.boot;

import static org.assertj.core.api.Assertions.assertThat;

import dev.ngb.loyalty.Application;
import dev.ngb.loyalty.support.LoyaltyIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;

/** The api runtime starts on its own: HTTP and the actuator, no job, no replica rebuild. */
@SpringBootTest(classes = Application.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("api")
class ApiRuntimeStartupTest extends LoyaltyIntegrationTest {

    @Autowired ApplicationContext context;

    @Test
    void theApiRuntimeStarts() {
        assertThat(context.getBean(Application.class)).isNotNull();
    }
}
