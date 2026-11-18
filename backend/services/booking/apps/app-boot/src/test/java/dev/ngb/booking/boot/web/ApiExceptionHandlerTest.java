package dev.ngb.booking.boot.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.ngb.booking.shared.ConditionalRequests;
import dev.ngb.booking.shared.PreconditionRequiredException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/** The missing-tag answer: {@code 428}, not {@code 412}. */
class ApiExceptionHandlerTest {

    private final ApiExceptionHandler handler = new ApiExceptionHandler();

    @Test
    void aMissingTagIsA428NotA412() {
        assertThat(handler.preconditionRequired(new PreconditionRequiredException()).getStatus())
                .isEqualTo(HttpStatus.PRECONDITION_REQUIRED.value());
    }

    @Test
    void aConditionalEndpointRefusesToRunWithoutATag() {
        assertThatThrownBy(() -> ConditionalRequests.requireIfMatch(null))
                .isInstanceOf(PreconditionRequiredException.class);
        assertThatThrownBy(() -> ConditionalRequests.requireIfMatch("  "))
                .isInstanceOf(PreconditionRequiredException.class);
        assertThat(ConditionalRequests.requireIfMatch("*")).isNotNull();
        assertThat(ConditionalRequests.requireIfMatch("\"3\"")).isNotNull();
    }
}
