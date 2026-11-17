package dev.ngb.workflow.definitions.domain.definition;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * The digest of a graph document that identifies what was published.
 *
 * <p>The document is written canonically first: object members in key order and numbers stripped of trailing zeros, so
 * two spellings of the same graph ({@code {"b":1,"a":2}} and {@code {"a":2.0,"b":1}}) produce one digest. The value is a
 * hex SHA-256, which is what the version row stores.
 */
public final class ContentHash {

    private ContentHash() {}

    public static String of(Object document) {
        StringBuilder canonical = new StringBuilder();
        write(document, canonical);
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(canonical.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is part of every JVM", e);
        }
    }

    private static void write(Object value, StringBuilder out) {
        if (value == null) {
            out.append("null");
        } else if (value instanceof String text) {
            quote(text, out);
        } else if (value instanceof Boolean flag) {
            out.append(flag);
        } else if (value instanceof Number number) {
            out.append(number(number));
        } else if (value instanceof Map<?, ?> members) {
            Map<String, Object> sorted = new TreeMap<>();
            members.forEach((key, member) -> sorted.put(String.valueOf(key), member));
            out.append('{');
            boolean first = true;
            for (Map.Entry<String, Object> entry : sorted.entrySet()) {
                if (!first) {
                    out.append(',');
                }
                first = false;
                quote(entry.getKey(), out);
                out.append(':');
                write(entry.getValue(), out);
            }
            out.append('}');
        } else if (value instanceof List<?> members) {
            out.append('[');
            for (int index = 0; index < members.size(); index++) {
                if (index > 0) {
                    out.append(',');
                }
                write(members.get(index), out);
            }
            out.append(']');
        } else {
            quote(String.valueOf(value), out);
        }
    }

    private static String number(Number value) {
        try {
            BigDecimal decimal = new BigDecimal(value.toString());
            BigDecimal stripped = decimal.stripTrailingZeros();
            return stripped.signum() == 0 ? "0" : stripped.toPlainString();
        } catch (NumberFormatException notADecimal) {
            return value.toString();
        }
    }

    private static void quote(String text, StringBuilder out) {
        out.append('"');
        for (int index = 0; index < text.length(); index++) {
            char character = text.charAt(index);
            switch (character) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (character < 0x20) {
                        out.append(String.format("\\u%04x", (int) character));
                    } else {
                        out.append(character);
                    }
                }
            }
        }
        out.append('"');
    }
}
