package dev.ngb.core.openapi;

import dev.ngb.core.security.Caller;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;
import tools.jackson.dataformat.yaml.YAMLMapper;

/**
 * Writes one OpenAPI document per caller and app under {@code contracts/openapi/<caller>/<app>.yaml}, filtered from the
 * generated document. A path with two callers (until the prefixes split it) appears in both files, with its full label;
 * a caller with no operation gets no file.
 *
 * <p>Under {@code -Dopenapi.update=true} it writes and removes stale files; otherwise it fails when a committed file is
 * missing, stale or different, so the per-caller documents cannot drift from the generated one.
 */
public final class CallerOpenApiSplitter {

    private static final YAMLMapper YAML = YAMLMapper.builder().build();

    private CallerOpenApiSplitter() {}

    /** @param openApiRoot the repository's {@code contracts/openapi} directory */
    public static void writeOrVerify(String document, Path openApiRoot, String app, boolean update) throws IOException {
        JsonNode root = YAML.readTree(document);
        for (Caller caller : Caller.values()) {
            String filtered = split(root, caller.wire());
            Path target = openApiRoot.resolve(caller.wire()).resolve(app + ".yaml");
            if (filtered == null) {
                if (update) {
                    Files.deleteIfExists(target);
                } else if (Files.exists(target)) {
                    throw new AssertionError("stale per-caller file with no operation: " + target);
                }
                continue;
            }
            if (update) {
                Files.createDirectories(target.getParent());
                Files.writeString(target, filtered, StandardCharsets.UTF_8);
                continue;
            }
            if (!Files.exists(target)) {
                throw new AssertionError("missing per-caller file " + target
                        + "; regenerate with -Dopenapi.update=true");
            }
            if (!Files.readString(target, StandardCharsets.UTF_8).equals(filtered)) {
                throw new AssertionError("per-caller file is out of date: " + target
                        + "; regenerate with -Dopenapi.update=true");
            }
        }
    }

    /** The document filtered to one caller, or null when that caller has no operation. */
    static String split(JsonNode document, String callerWire) {
        JsonNode copy = document.deepCopy();
        JsonNode paths = copy.get("paths");
        if (paths == null || !paths.isObject()) {
            return null;
        }
        ObjectNode objectPaths = (ObjectNode) paths;
        for (String path : new ArrayList<>(objectPaths.propertyNames())) {
            JsonNode item = objectPaths.get(path);
            if (item == null || !item.isObject()) {
                objectPaths.remove(path);
                continue;
            }
            ObjectNode objectItem = (ObjectNode) item;
            List<String> drop = new ArrayList<>();
            boolean kept = false;
            for (String method : objectItem.propertyNames()) {
                JsonNode operation = objectItem.get(method);
                if (!isOperation(operation)) {
                    continue;
                }
                if (hasCaller(operation, callerWire)) {
                    kept = true;
                } else {
                    drop.add(method);
                }
            }
            if (!kept) {
                objectPaths.remove(path);
            } else {
                drop.forEach(objectItem::remove);
            }
        }
        if (objectPaths.isEmpty()) {
            return null;
        }
        return YAML.writeValueAsString(copy);
    }

    private static boolean isOperation(JsonNode node) {
        return node != null && node.isObject() && node.get("operationId") != null;
    }

    private static boolean hasCaller(JsonNode operation, String callerWire) {
        JsonNode labels = operation.get("x-allowed-callers");
        if (labels == null || !labels.isArray()) {
            return false;
        }
        for (JsonNode label : labels) {
            if (callerWire.equals(label.asString())) {
                return true;
            }
        }
        return false;
    }
}
