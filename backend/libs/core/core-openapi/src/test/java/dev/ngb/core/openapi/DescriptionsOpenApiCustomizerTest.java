package dev.ngb.core.openapi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.Paths;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.IntegerSchema;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.ObjectSchema;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.parameters.Parameter;
import io.swagger.v3.oas.models.parameters.RequestBody;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;
import java.util.List;
import org.junit.jupiter.api.Test;

class DescriptionsOpenApiCustomizerTest {

    private final DescriptionsOpenApiCustomizer customizer = new DescriptionsOpenApiCustomizer("descriptions-test.conf");

    private static Schema<?> prop(Schema<?> schema, String name) {
        return (Schema<?>) schema.getProperties().get(name);
    }

    private static Schema<?> ref(String name) {
        return new Schema<>().$ref("#/components/schemas/" + name);
    }

    private static Parameter param(String name, String in) {
        return new Parameter().name(name).in(in).schema(new StringSchema());
    }

    private static OpenAPI document() {
        ObjectSchema role = new ObjectSchema();
        role.addProperty("id", new StringSchema());
        role.addProperty("name", new StringSchema());
        role.addProperty("version", new IntegerSchema());
        role.addProperty("undocumented", new StringSchema());
        ObjectSchema create = new ObjectSchema();
        create.addProperty("name", new StringSchema());
        ObjectSchema pageInfo = new ObjectSchema();
        pageInfo.addProperty("limit", new IntegerSchema());
        ObjectSchema page = new ObjectSchema();
        page.addProperty("data", new Schema<>());
        page.addProperty("page", ref("PageInfo"));

        Operation update = new Operation()
                .addParametersItem(param("id", "path"))
                .addParametersItem(param("If-Match", "header"))
                .addParametersItem(param("locale", "query"))
                .addParametersItem(param("roleId", "path"))
                .addParametersItem(param("other", "query"))
                .addParametersItem(param("kept", "query").description("Already described."))
                .requestBody(new RequestBody().content(new Content().addMediaType("application/json", new MediaType().schema(ref("CreateRole")))))
                .responses(new ApiResponses()
                        .addApiResponse("200", new ApiResponse().description("OK")
                                .content(new Content().addMediaType("*/*", new MediaType().schema(ref("Role")))))
                        .addApiResponse("409", new ApiResponse().description("OK")
                                .content(new Content().addMediaType("*/*", new MediaType().schema(ref("Role"))))));
        Operation other = new Operation().addParametersItem(param("roleId", "path"));
        return new OpenAPI()
                .components(new Components()
                        .addSchemas("Role", role)
                        .addSchemas("CreateRole", create)
                        .addSchemas("PageInfo", pageInfo)
                        .addSchemas("PageRole", page))
                .paths(new Paths()
                        .addPathItem("/api/v1/roles/{id}", new PathItem().put(update))
                        .addPathItem("/api/v1/role-assignments/{roleId}", new PathItem().get(other))
                        .addPathItem("/api/v1/tenant-systems/{id}", new PathItem().get(new Operation().addParametersItem(param("id", "path"))))
                        .addPathItem("/api/v1/users/{userId}", new PathItem().get(new Operation().addParametersItem(param("userId", "path")))));
    }

    @Test
    void aSchemaAndItsFieldsTakeTheirOwnEntryBeforeTheSharedFieldEntry() {
        OpenAPI api = document();
        customizer.customise(api);
        Schema<?> role = api.getComponents().getSchemas().get("Role");

        assertThat(role.getDescription()).isEqualTo("A role and the permissions it grants.");
        assertThat(prop(role, "name").getDescription()).isEqualTo("The role's display name.");
        assertThat(prop(role, "id").getDescription()).isEqualTo("The id.");
        assertThat(prop(role, "version").getDescription()).isEqualTo("The version of the role, also its ETag.");
        assertThat(prop(role, "undocumented").getDescription()).isNull();
    }

    @Test
    void theBuiltInPagingFactsAndTheWrapperAreDescribed() {
        OpenAPI api = document();
        customizer.customise(api);

        assertThat(prop(api.getComponents().getSchemas().get("PageInfo"), "limit").getDescription())
                .isEqualTo("The page size that was applied.");
        Schema<?> wrapper = api.getComponents().getSchemas().get("PageRole");
        assertThat(wrapper.getDescription()).startsWith("A page of role rows");
        assertThat(prop(wrapper, "data").getDescription()).isEqualTo("The rows of this page.");
        assertThat(prop(wrapper, "page").getDescription()).contains("`page_info`");
    }

    @Test
    void aParameterTakesItsPathEntryThenItsNameEntryThenTheBuiltInOne() {
        OpenAPI api = document();
        customizer.customise(api);
        List<Parameter> parameters = api.getPaths().get("/api/v1/roles/{id}").getPut().getParameters();

        assertThat(parameters.get(0).getDescription()).isEqualTo("The id of the role to act on.");
        assertThat(parameters.get(1).getDescription()).isEqualTo("Overridden by the service.");
        assertThat(parameters.get(2).getDescription()).isEqualTo("The locale the text is rendered in.");
        assertThat(parameters.get(4).getDescription()).isNull();
        assertThat(parameters.get(5).getDescription()).isEqualTo("Already described.");
    }

    @Test
    void aPathParameterNobodyDescribedIsDerivedFromTheResourceBeforeIt() {
        OpenAPI api = document();
        customizer.customise(api);

        assertThat(api.getPaths().get("/api/v1/role-assignments/{roleId}").getGet().getParameters().get(0).getDescription())
                .isEqualTo("The role id of the role assignment.");
        assertThat(api.getPaths().get("/api/v1/tenant-systems/{id}").getGet().getParameters().get(0).getDescription())
                .isEqualTo("The id of the tenant system.");
        assertThat(api.getPaths().get("/api/v1/users/{userId}").getGet().getParameters().get(0).getDescription())
                .isEqualTo("The user id.");
    }

    @Test
    void aBodyAndASuccessfulResponseTakeTheDescriptionOfTheirSchema() {
        OpenAPI api = document();
        customizer.customise(api);
        Operation update = api.getPaths().get("/api/v1/roles/{id}").getPut();

        assertThat(update.getRequestBody().getDescription()).isEqualTo("The body that creates a role.");
        assertThat(update.getResponses().get("200").getDescription()).isEqualTo("A role and the permissions it grants.");
        assertThat(update.getResponses().get("409").getDescription()).isEqualTo("OK");
    }

    @Test
    void aBodyWithoutASchemaTakesItsPathEntryAndAFormFieldItsOwn() {
        ObjectSchema form = new ObjectSchema();
        form.addProperty("grant_type", new StringSchema());
        form.addProperty("audience", new StringSchema());
        Operation token = new Operation().requestBody(new RequestBody()
                .content(new Content().addMediaType("application/x-www-form-urlencoded", new MediaType().schema(form))));
        OpenAPI api = new OpenAPI().paths(new Paths().addPathItem("/oauth/token", new PathItem().post(token)));
        customizer.customise(api);

        assertThat(token.getRequestBody().getDescription()).isEqualTo("The token request, as a form.");
        assertThat(prop(form, "grant_type").getDescription()).isEqualTo("Must be client_credentials.");
        assertThat(prop(form, "audience").getDescription()).isNull();
    }

    @Test
    void aMissingFileFailsAtStartup() {
        assertThatThrownBy(() -> new DescriptionsOpenApiCustomizer("no-such-file.conf"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no-such-file.conf");
    }
}
