package dev.ngb.core.feign;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import feign.Request;
import feign.RetryableException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.Test;

class LinearRetryerTest {

    @Test
    void retriesUntilTheAttemptBudgetIsSpent() {
        LinearRetryer retryer = new LinearRetryer(3, 1, FeignFailures.core());
        RetryableException refused = refused();

        retryer.continueOrPropagate(refused);
        retryer.continueOrPropagate(refused);

        assertThatThrownBy(() -> retryer.continueOrPropagate(refused)).isSameAs(refused);
    }

    @Test
    void aSingleAttemptBudgetNeverRetries() {
        LinearRetryer retryer = new LinearRetryer(1, 1, FeignFailures.core());
        RetryableException refused = refused();

        assertThatThrownBy(() -> retryer.continueOrPropagate(refused)).isSameAs(refused);
    }

    private static RetryableException refused() {
        Request request = Request.create(
                Request.HttpMethod.GET, "http://upstream/internal/replica/tenants", Map.of(), null,
                StandardCharsets.UTF_8, null);
        return new RetryableException(503, "upstream is down", Request.HttpMethod.GET, (Long) null, request);
    }
}
