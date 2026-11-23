package dev.ngb.core.openapi;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import tools.jackson.databind.JsonNode;
import tools.jackson.dataformat.yaml.YAMLMapper;

/**
 * The label rules every OpenAPI contract test runs: each operation names at least one of the seven callers, names
 * only known ones, and never carries the retired {@code x-public} flag.
 *
 * <p>The one-caller-per-path invariant is switchable because it only becomes true as the prefixes land: until phase 6
 * an app serves one path for both {@code tenant-user} and {@code tenant-system}. A contract test passes
 * {@code singleCallerPerPath = false} until its app's paths are split, then true. The two deliberate shared paths,
 * {@code /api/v1/me/**} and {@code /oauth/token}, are always allowed two callers.
 */
public final class CallerLabelRules {

    private static final YAMLMapper YAML = YAMLMapper.builder().build();

    private static final Set<String> CALLERS = Set.of(
            "platform-user", "tenant-user", "tenant-system", "customer-session", "public", "provider", "internal");

    private CallerLabelRules() {}

    /** @param document the YAML text of an OpenAPI document */
    public static void check(String document, boolean singleCallerPerPath) {
        check(document, singleCallerPerPath, false);
    }

    /**
     * @param singleCallerPerPath fail when one path names two callers
     * @param callerPrefixStrict fail when a path under a caller prefix names a caller that prefix does not serve; on
     *     per app only once its routes are mounted by the phase-5 mechanism, because until then a route still spells
     *     {@code /api/v1} while carrying {@code tenant-system}
     */
    public static void check(String document, boolean singleCallerPerPath, boolean callerPrefixStrict) {
        check(YAML.readTree(document), singleCallerPerPath, callerPrefixStrict);
    }

    static void check(JsonNode document, boolean singleCallerPerPath) {
        check(document, singleCallerPerPath, false);
    }

    static void check(JsonNode document, boolean singleCallerPerPath, boolean callerPrefixStrict) {
        JsonNode paths = document.get("paths");
        if (paths == null || !paths.isObject()) {
            throw new AssertionError("the document has no paths");
        }
        for (String path : paths.propertyNames()) {
            JsonNode item = paths.get(path);
            if (item == null || !item.isObject()) {
                continue;
            }
            Set<String> onPath = new LinkedHashSet<>();
            for (String method : item.propertyNames()) {
                JsonNode operation = item.get(method);
                if (!isOperation(operation)) {
                    continue;
                }
                JsonNode labels = operation.get("x-allowed-callers");
                if (labels == null || !labels.isArray() || labels.isEmpty()) {
                    throw new AssertionError("no caller on " + method.toUpperCase() + " " + path);
                }
                for (JsonNode label : labels) {
                    String caller = label.asString();
                    if (!CALLERS.contains(caller)) {
                        throw new AssertionError("unknown caller '" + caller + "' on " + method.toUpperCase() + " " + path);
                    }
                    if (callerPrefixStrict) {
                        checkPrefix(path, caller);
                    }
                    onPath.add(caller);
                }
                if (operation.get("x-public") != null) {
                    throw new AssertionError("retired x-public on " + method.toUpperCase() + " " + path);
                }
            }
            if (singleCallerPerPath && onPath.size() > 1 && !sharedByDesign(path)) {
                throw new AssertionError(path + " has more than one caller: " + onPath
                        + "; only /api/v1/me/** and /oauth/token may share a path");
            }
        }
    }

    /**
     * A caller prefix serves exactly the callers it implies. A route that spells the prefix but names another caller
     * is how a moved path silently serves the wrong token kind, so this guards against bypassing the mechanism.
     */
    private static void checkPrefix(String path, String caller) {
        String prefix = callerPrefix(path);
        if (prefix == null) {
            return;
        }
        Set<String> serves = switch (prefix) {
            case "/api/v1" -> Set.of("tenant-user", "platform-user");
            case "/admin/v1" -> Set.of("platform-user");
            case "/system/v1" -> Set.of("tenant-system");
            case "/internal/v1" -> Set.of("internal");
            default -> null;
        };
        if (serves != null && !serves.contains(caller)) {
            throw new AssertionError(path + " is under " + prefix + " but names " + caller);
        }
    }

    private static String callerPrefix(String path) {
        for (String prefix : List.of("/api/v1/me", "/api/v1", "/admin/v1", "/system/v1", "/internal/v1")) {
            if (path.equals(prefix) || path.startsWith(prefix + "/")) {
                return prefix;
            }
        }
        return null;
    }

    private static boolean isOperation(JsonNode node) {
        return node != null && node.isObject() && node.get("operationId") != null;
    }

    private static boolean sharedByDesign(String path) {
        return path.equals("/api/v1/me") || path.startsWith("/api/v1/me/") || path.equals("/oauth/token");
    }
}
