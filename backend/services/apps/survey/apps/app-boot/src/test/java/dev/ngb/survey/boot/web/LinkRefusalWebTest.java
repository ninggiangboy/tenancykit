package dev.ngb.survey.boot.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.ngb.survey.collection.application.link.exception.LinkRefusedException;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * A link a tenant asks for and the suite refuses must come back as a client error a caller can act on, never as the
 * {@code 500} an exception nobody mapped becomes.
 */
class LinkRefusalWebTest {

    @RestController
    static class RefusingController {
        @PostMapping("/refuse/{reason}")
        void refuse(@PathVariable LinkRefusedException.Reason reason) {
            throw new LinkRefusedException(reason, "The link was refused.");
        }
    }

    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new RefusingController())
            .setControllerAdvice(new ApiExceptionHandler())
            .build();

    @ParameterizedTest
    @CsvSource({"SOURCE_TAKEN,409", "SURVEY_NOT_OPEN,409", "DUPLICATE_REFERENCE,422"})
    void eachReasonIsAProblemWithAClientStatus(String reason, int expected) throws Exception {
        mvc.perform(post("/refuse/" + reason))
                .andExpect(status().is(expected))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(expected))
                .andExpect(jsonPath("$.detail").value("The link was refused."));
    }
}
