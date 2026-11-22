package dev.ngb.core.openapi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.Paths;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;
import java.util.List;
import org.junit.jupiter.api.Test;

class StatusLinesOpenApiCustomizerTest {

    private final DescriptionsOpenApiCustomizer customizer = new DescriptionsOpenApiCustomizer("descriptions-status-test.conf");

    private static Content thing() {
        return new Content().addMediaType("application/json", new MediaType().schema(new Schema<>().$ref("#/components/schemas/Thing")));
    }

    private static Operation answering(String code, ApiResponse response) {
        return new Operation().responses(new ApiResponses().addApiResponse(code, response)
                .addApiResponse("404", new ApiResponse().description("Not found")));
    }

    private static OpenAPI document(Operation create, Operation delete, Operation redirect) {
        return new OpenAPI().paths(new Paths()
                .addPathItem("/api/v1/things", new PathItem().post(create))
                .addPathItem("/api/v1/things/{id}", new PathItem().delete(delete))
                .addPathItem("/go/{slug}", new PathItem().get(redirect)));
    }

    @Test
    void anOperationThatAnswersTwoStatusesGetsBothWithTheBodyOfTheOneItHad() {
        Operation create = answering("200", new ApiResponse().description("A thing.").content(thing()));
        customizer.customise(document(create, answering("200", new ApiResponse().description("OK")),
                answering("200", new ApiResponse().description("OK"))));

        assertThat(List.copyOf(create.getResponses().keySet())).containsExactly("200", "201", "404");
        assertThat(create.getResponses().get("201").getDescription()).isEqualTo("The thing was created.");
        assertThat(create.getResponses().get("201").getContent()).isSameAs(create.getResponses().get("200").getContent());
        assertThat(create.getResponses().get("200").getDescription()).isEqualTo("The thing existed already, and is returned.");
    }

    @Test
    void aNoContentStatusReplacesTheSuccessAndCarriesNoBody() {
        Operation delete = answering("200", new ApiResponse().description("OK").content(thing()));
        customizer.customise(document(answering("200", new ApiResponse().description("A thing.")), delete,
                answering("200", new ApiResponse().description("OK"))));

        assertThat(List.copyOf(delete.getResponses().keySet())).containsExactly("204", "404");
        assertThat(delete.getResponses().get("204").getDescription()).isEqualTo("The thing was removed.");
        assertThat(delete.getResponses().get("204").getContent()).isNull();
    }

    @Test
    void aStatusLineWithNoTextKeepsTheDescriptionOfTheResponseItDeclares() {
        Operation redirect = answering("200", new ApiResponse().description("The link has expired."));
        customizer.customise(document(answering("200", new ApiResponse().description("A thing.")),
                answering("200", new ApiResponse().description("OK")), redirect));

        assertThat(List.copyOf(redirect.getResponses().keySet())).containsExactly("200", "302", "404");
        assertThat(redirect.getResponses().get("200").getDescription()).isEqualTo("The link has expired.");
        assertThat(redirect.getResponses().get("302").getDescription()).isEqualTo("Redirects to the destination.");
        assertThat(redirect.getResponses().get("302").getContent()).isNull();
    }

    @Test
    void aStatusLineForAnOperationThatDoesNotExistFailsAtStartup() {
        OpenAPI api = new OpenAPI().paths(new Paths().addPathItem("/api/v1/things", new PathItem().post(
                answering("200", new ApiResponse().description("A thing.")))));

        assertThatThrownBy(() -> customizer.customise(api))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("DELETE /api/v1/things/{id}");
    }
}
