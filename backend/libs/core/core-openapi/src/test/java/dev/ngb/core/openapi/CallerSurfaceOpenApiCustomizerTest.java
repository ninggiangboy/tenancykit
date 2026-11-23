package dev.ngb.core.openapi;

import static org.assertj.core.api.Assertions.assertThat;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.Paths;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * A handler documented at two prefixes is narrowed to the caller each prefix serves, and its twin gets an id that names
 * that caller instead of springdoc's order-dependent {@code _1}.
 */
class CallerSurfaceOpenApiCustomizerTest {

    private static Operation operation(String id, String... callers) {
        Operation operation = new Operation().operationId(id);
        operation.addExtension(CallerLabelCustomizer.X_ALLOWED_CALLERS, List.of(callers));
        operation.setDescription("Reads a wallet.\n\n" + CallerLabelCustomizer.line(
                callers.length == 2
                        ? java.util.EnumSet.of(
                                dev.ngb.core.security.Caller.TENANT_USER,
                                dev.ngb.core.security.Caller.TENANT_SYSTEM)
                        : java.util.EnumSet.of(dev.ngb.core.security.Caller.TENANT_USER),
                null));
        return operation;
    }

    private static OpenAPI twoPrefixes() {
        OpenAPI api = new OpenAPI().paths(new Paths());
        api.path("/api/v1/wallets", new PathItem().get(operation("list", "tenant-user", "tenant-system")));
        api.path("/system/v1/wallets", new PathItem().get(operation("list", "tenant-user", "tenant-system")));
        return api;
    }

    @SuppressWarnings("unchecked")
    private static List<String> labels(OpenAPI api, String path) {
        PathItem item = api.getPaths().get(path);
        Operation operation = item.getGet() != null ? item.getGet() : item.getPost();
        return (List<String>) operation.getExtensions().get(CallerLabelCustomizer.X_ALLOWED_CALLERS);
    }

    @Test
    void aMirroredRouteNarrowsToTheCallerEachPrefixServes() {
        OpenAPI api = twoPrefixes();
        new CallerSurfaceOpenApiCustomizer().customise(api);

        assertThat(labels(api, "/api/v1/wallets")).containsExactly("tenant-user");
        assertThat(labels(api, "/system/v1/wallets")).containsExactly("tenant-system");
    }

    @Test
    void theDescriptionLineFollowsTheLabel() {
        OpenAPI api = twoPrefixes();
        new CallerSurfaceOpenApiCustomizer().customise(api);

        assertThat(api.getPaths().get("/api/v1/wallets").getGet().getDescription())
                .isEqualTo("Reads a wallet.\n\nCallers: tenant-user.");
        assertThat(api.getPaths().get("/system/v1/wallets").getGet().getDescription())
                .isEqualTo("Reads a wallet.\n\nCallers: tenant-system.");
    }

    @Test
    void thePersonPathKeepsTheBaseIdAndTheTwinNamesItsCaller() {
        OpenAPI api = twoPrefixes();
        new CallerSurfaceOpenApiCustomizer().customise(api);

        assertThat(api.getPaths().get("/api/v1/wallets").getGet().getOperationId()).isEqualTo("list");
        assertThat(api.getPaths().get("/system/v1/wallets").getGet().getOperationId())
                .isEqualTo("listAsTenantSystem");
    }

    @Test
    void theSharedMePathKeepsBothPersonKinds() {
        OpenAPI api = new OpenAPI().paths(new Paths());
        api.path("/api/v1/me/notifications",
                new PathItem().get(operation("list", "platform-user", "tenant-user")));
        new CallerSurfaceOpenApiCustomizer().customise(api);

        assertThat(labels(api, "/api/v1/me/notifications")).containsExactly("platform-user", "tenant-user");
    }

    @Test
    void aPathOutsideEveryPrefixIsLeftAlone() {
        OpenAPI api = new OpenAPI().paths(new Paths());
        api.path("/oauth/token", new PathItem().post(operation("issue", "tenant-system", "internal")));
        new CallerSurfaceOpenApiCustomizer().customise(api);

        assertThat(labels(api, "/oauth/token")).containsExactly("tenant-system", "internal");
        assertThat(api.getPaths().get("/oauth/token").getPost().getOperationId()).isEqualTo("issue");
    }
}
