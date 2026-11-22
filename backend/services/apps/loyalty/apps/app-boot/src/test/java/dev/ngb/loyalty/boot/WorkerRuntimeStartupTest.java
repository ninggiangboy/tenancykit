package dev.ngb.loyalty.boot;

import static org.assertj.core.api.Assertions.assertThat;

import dev.ngb.loyalty.Application;
import dev.ngb.loyalty.support.LoyaltyIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;

/**
 * The worker runtime starts on its own: the replica listener, the outbox relay, the webhook delivery round and
 * housekeeping. The rebuild is switched off, so the context starts without a Platform to page.
 */
@SpringBootTest(classes = Application.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("worker")
class WorkerRuntimeStartupTest extends LoyaltyIntegrationTest {

    @Autowired ApplicationContext context;

    @Test
    void theWorkerRuntimeStarts() {
        assertThat(context.getBean(Application.class)).isNotNull();
    }
}
