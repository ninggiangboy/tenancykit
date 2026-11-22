package dev.ngb.core.openapi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.Paths;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.parameters.Parameter;
import io.swagger.v3.oas.models.parameters.RequestBody;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ErrorResponsesOpenApiCustomizerTest {

    private final ErrorResponsesOpenApiCustomizer customizer = new ErrorResponsesOpenApiCustomizer("descriptions-test.conf");

    private static Parameter header(String name) {
        return new Parameter().name(name).in("header").schema(new StringSchema());
    }

    private static Operation success() {
        return new Operation().responses(new ApiResponses().addApiResponse("200", new ApiResponse().description("OK")));
    }

    private static List<String> statuses(Operation operation) {
        return List.copyOf(operation.getResponses().keySet());
    }

    private static OpenAPI document(Operation update, Operation list, Operation publicRead) {
        return new OpenAPI().paths(new Paths()
                .addPathItem("/api/v1/roles/{id}", new PathItem().put(update))
                .addPathItem("/api/v1/roles", new PathItem().get(list))
                .addPathItem("/t/o/{token}", new PathItem().get(publicRead)));
    }

    private static Operation with(Operation operation, String extension, Object value) {
        operation.addExtension(extension, value);
        return operation;
    }

    private static Operation guarded(Operation operation) {
        return with(operation, "x-required-permission", "platform:role:manage");
    }

    @Test
    void anOperationListsTheErrorsItsOwnShapeCanAnswer() {
        Operation update = guarded(success())
                .addParametersItem(header("If-Match"))
                .addParametersItem(header("Idempotency-Key"))
                .addParametersItem(new Parameter().name("id").in("path").schema(new StringSchema()))
                .requestBody(new RequestBody().content(new Content().addMediaType("application/json", new MediaType())));
        Operation list = with(success(), "x-allowed-callers", List.of("tenant-user"));
        Operation publicRead = with(success(), "x-allowed-callers", List.of("public"))
                .addParametersItem(new Parameter().name("token").in("path").schema(new StringSchema()));
        OpenAPI api = document(update, list, publicRead);
        customizer.customise(api);

        assertThat(statuses(update)).containsExactly("200", "400", "401", "403", "404", "409", "412", "422");
        assertThat(update.getResponses().get("401").get$ref()).isEqualTo("#/components/responses/Unauthenticated");
        assertThat(update.getResponses().get("403").get$ref()).isEqualTo("#/components/responses/Forbidden");
        assertThat(update.getResponses().get("412").get$ref()).isEqualTo("#/components/responses/PreconditionFailed");
        assertThat(statuses(list)).containsExactly("200", "401", "403");
        assertThat(list.getResponses().get("403").get$ref()).isEqualTo("#/components/responses/NotEntitled");
        assertThat(statuses(publicRead)).containsExactly("200");
    }

    @Test
    void aLineOfTheServicesFileReplacesTheDerivedResponseAndNamesItsBody() {
        Operation update = guarded(success()).addParametersItem(header("Idempotency-Key"));
        OpenAPI api = document(update, success(), success());
        customizer.customise(api);

        ApiResponse conflict = update.getResponses().get("409");
        assertThat(conflict.get$ref()).isNull();
        assertThat(conflict.getDescription()).isEqualTo("The role is a built-in one and cannot be changed.");
        assertThat(conflict.getContent().get("application/problem+json").getSchema().get$ref())
                .isEqualTo("#/components/schemas/ProblemDetail");
        assertThat(update.getResponses().get("422").getContent().get("application/problem+json").getSchema().get$ref())
                .isEqualTo("#/components/schemas/Report");
    }

    @Test
    void anErrorTheOperationAlreadyHasKeepsItsTextAndGainsTheBody() {
        Operation list = guarded(success());
        list.getResponses().addApiResponse("400", new ApiResponse().description("A filter is not valid."));
        OpenAPI api = document(success(), list, success());
        customizer.customise(api);

        ApiResponse badRequest = list.getResponses().get("400");
        assertThat(badRequest.getDescription()).isEqualTo("A filter is not valid.");
        assertThat(badRequest.getContent().get("application/problem+json").getSchema().get$ref()).isEqualTo("#/components/schemas/ProblemDetail");
    }

    @Test
    void aFreeTextParameterCannotBeMalformedButATypedOrRequiredOneCan() {
        Operation free = guarded(success())
                .addParametersItem(new Parameter().name("slug").in("path").schema(new StringSchema()))
                .addParametersItem(new Parameter().name("e").in("query").required(false).schema(new StringSchema()));
        Operation typed = guarded(success())
                .addParametersItem(new Parameter().name("limit").in("query").required(false).schema(new io.swagger.v3.oas.models.media.IntegerSchema()));
        Operation required = guarded(success())
                .addParametersItem(new Parameter().name("email").in("query").required(true).schema(new StringSchema()));
        OpenAPI api = new OpenAPI().paths(new Paths()
                .addPathItem("/a/{slug}", new PathItem().get(free))
                .addPathItem("/b", new PathItem().get(typed))
                .addPathItem("/c", new PathItem().get(required))
                .addPathItem("/api/v1/roles/{id}", new PathItem().put(success())));
        customizer.customise(api);

        assertThat(statuses(free)).containsExactly("200", "401", "403", "404");
        assertThat(statuses(typed)).contains("400");
        assertThat(statuses(required)).contains("400");
    }

    @Test
    void anErrorLineWithNoTextRemovesTheResponseTheOperationNeverAnswers() {
        Operation events = guarded(success()).addParametersItem(new Parameter().name("id").in("path").schema(new StringSchema()));
        OpenAPI api = new OpenAPI().paths(new Paths().addPathItem("/api/v1/events", new PathItem().get(events)));
        new ErrorResponsesOpenApiCustomizer("descriptions-removal-test.conf").customise(api);

        assertThat(statuses(events)).containsExactly("200", "403");
    }

    @Test
    void anErrorLineForAnOperationThatDoesNotExistFailsAtStartup() {
        OpenAPI api = new OpenAPI().paths(new Paths().addPathItem("/api/v1/roles", new PathItem().get(success())));

        assertThatThrownBy(() -> customizer.customise(api))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("PUT /api/v1/roles/{id}");
    }

    @Test
    void theSharedResponsesAndTheProblemSchemaAreComponents() {
        OpenAPI api = document(success(), success(), success());
        customizer.customise(api);

        assertThat(api.getComponents().getResponses()).containsKeys(
                "BadRequest", "Unauthenticated", "Forbidden", "NotEntitled", "NotFound", "IdempotencyConflict", "PreconditionFailed");
        assertThat(api.getComponents().getResponses().get("PreconditionFailed").getHeaders()).containsKey("ETag");
        Map<?, ?> properties = api.getComponents().getSchemas().get("ProblemDetail").getProperties();
        for (String field : List.of("type", "title", "status", "detail", "instance")) {
            assertThat(properties.containsKey(field)).as(field).isTrue();
        }
    }
}
