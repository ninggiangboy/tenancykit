package dev.ngb.core.openapi;

import dev.ngb.core.security.Caller;
import dev.ngb.core.security.CallerPrefixes;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springdoc.core.customizers.GlobalOpenApiCustomizer;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.util.StringUtils;

/**
 * Makes each documented path name the one caller its prefix serves, and gives a twin path a stable operation id.
 *
 * <p>{@link CallerLabelCustomizer} declares who may call the handler. A handler mounted at two prefixes is documented
 * twice, so this pass narrows each operation to the callers its own prefix accepts ({@code /api/v1} keeps the person
 * kinds, {@code /system/v1} keeps {@code tenant-system}, and so on); the shared {@code /api/v1/me/**} keeps both.
 * Without it one path would carry two labels and a reader could not tell who may call the route in front of them.
 *
 * <p>It also pins the id a mirrored route gets. springdoc de-duplicates a repeated operation id by appending
 * {@code _1} in whatever order it visits the paths, which renumbers the canonical route. Here a twin of the same
 * resource gets a caller-derived suffix instead ({@code list} and {@code listAsTenantSystem}); a genuine clash of two
 * different handlers that share a method name is left to springdoc, so its committed ids do not churn.
 *
 * <p>It runs before the de-duplication pass so that a twin already carries a unique id when that pass counts.
 */
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CallerSurfaceOpenApiCustomizer implements GlobalOpenApiCustomizer {

    @Override
    public void customise(OpenAPI api) {
        if (api.getPaths() == null) {
            return;
        }
        Map<String, List<Side>> byBaseId = new LinkedHashMap<>();
        api.getPaths().forEach((path, item) -> item.readOperationsMap().forEach((method, operation) ->
                byBaseId.computeIfAbsent(operation.getOperationId(), key -> new ArrayList<>())
                        .add(new Side(path, operation))));
        byBaseId.forEach(this::narrowToOneCallerPerPath);
    }

    /**
     * Narrows every operation to the callers its path accepts, then renames each twin of a canonical resource so its id
     * names the caller. Two operations are twins when they differ only by the prefix in front of the resource path.
     */
    private void narrowToOneCallerPerPath(String baseId, List<Side> sides) {
        for (Side side : sides) {
            narrow(side.operation(), side.path());
        }
        if (!StringUtils.hasText(baseId)) {
            return;
        }
        Map<String, Side> canonicalByResource = new LinkedHashMap<>();
        for (Side side : sides) {
            if (side.callerPrefix() != null && isPersonFacing(side.callerPrefix())) {
                canonicalByResource.put(side.resourcePath(), side);
            }
        }
        for (Side side : sides) {
            String prefix = side.callerPrefix();
            if (prefix == null || isPersonFacing(prefix) || !canonicalByResource.containsKey(side.resourcePath())) {
                continue;
            }
            side.operation().setOperationId(baseId + "As" + pascal(serves(prefix)));
        }
    }

    /** The callers a path accepts, or none when its path is not one a caller prefix owns. */
    static Set<Caller> impliedBy(String path) {
        if (under(path, CallerPrefixes.ME)) {
            return EnumSet.of(Caller.PLATFORM_USER, Caller.TENANT_USER);
        }
        if (under(path, CallerPrefixes.SYSTEM)) {
            return EnumSet.of(Caller.TENANT_SYSTEM);
        }
        if (under(path, CallerPrefixes.INTERNAL)) {
            return EnumSet.of(Caller.INTERNAL);
        }
        if (under(path, CallerPrefixes.ADMIN)) {
            return EnumSet.of(Caller.PLATFORM_USER);
        }
        if (under(path, CallerPrefixes.API)) {
            return EnumSet.of(Caller.TENANT_USER);
        }
        return EnumSet.noneOf(Caller.class);
    }

    private static boolean under(String path, String prefix) {
        return path.equals(prefix) || path.startsWith(prefix + "/");
    }

    /** Rewrites the label and the generated description line to the callers the path actually serves. */
    private static void narrow(Operation operation, String path) {
        if (operation.getExtensions() == null) {
            return;
        }
        Object declared = operation.getExtensions().get(CallerLabelCustomizer.X_ALLOWED_CALLERS);
        if (!(declared instanceof List<?> labels) || labels.isEmpty()) {
            return;
        }
        Set<Caller> serves = impliedBy(path);
        if (serves.isEmpty()) {
            return;
        }
        List<Caller> kept = new ArrayList<>();
        for (Object label : labels) {
            Caller.fromWire(String.valueOf(label)).filter(serves::contains).ifPresent(kept::add);
        }
        if (kept.size() == labels.size() || kept.isEmpty()) {
            return;
        }
        operation.addExtension(CallerLabelCustomizer.X_ALLOWED_CALLERS, kept.stream().map(Caller::wire).toList());
        Object code = operation.getExtensions().get(CallerLabelCustomizer.X_REQUIRED_PERMISSION);
        replaceCallersLine(operation, CallerLabelCustomizer.line(
                EnumSet.copyOf(kept), code == null ? null : String.valueOf(code)));
    }

    /** The description names the callers on one generated line; a narrowing rewrites exactly that line. */
    private static void replaceCallersLine(Operation operation, String line) {
        String description = operation.getDescription();
        if (description == null) {
            operation.setDescription(line);
            return;
        }
        List<String> parts = new ArrayList<>();
        boolean replaced = false;
        for (String part : description.split("\n\n")) {
            if (part.strip().startsWith("Callers: ")) {
                parts.add(line);
                replaced = true;
            } else {
                parts.add(part);
            }
        }
        operation.setDescription(replaced ? String.join("\n\n", parts) : description + "\n\n" + line);
    }

    /** The caller one single-caller prefix serves. */
    private static Caller serves(String prefix) {
        return impliedBy(prefix + "/x").iterator().next();
    }

    private static boolean isPersonFacing(String prefix) {
        return CallerPrefixes.API.equals(prefix)
                || CallerPrefixes.ADMIN.equals(prefix)
                || CallerPrefixes.ME.equals(prefix);
    }

    private static String pascal(Caller caller) {
        StringBuilder name = new StringBuilder();
        for (String part : caller.wire().split("-")) {
            if (!part.isEmpty()) {
                name.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
            }
        }
        return name.toString();
    }

    /** One appearance of an operation: the path it documents and the operation. */
    private record Side(String path, Operation operation) {

        /** The caller prefix in front of the path, or null when the path is not one a caller prefix owns. */
        String callerPrefix() {
            for (String prefix : List.of(
                    CallerPrefixes.ME, CallerPrefixes.SYSTEM, CallerPrefixes.INTERNAL,
                    CallerPrefixes.ADMIN, CallerPrefixes.API)) {
                if (under(path, prefix)) {
                    return prefix;
                }
            }
            return null;
        }

        /** The resource path below the caller prefix: two twins share it. */
        String resourcePath() {
            String prefix = callerPrefix();
            return prefix == null || path.length() == prefix.length() ? path : path.substring(prefix.length());
        }
    }
}
