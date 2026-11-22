package dev.ngb.mailroom.content.domain.template;

import dev.ngb.core.error.BadRequestException;
import java.nio.charset.StandardCharsets;

/**
 * The rules applied to a tenant-authored template before it is stored and again before it is rendered.
 *
 * <p>A template is tenant-controlled text and the data it renders is receiver-controlled, so the risk is a denial of
 * service and markup injection rather than logic. The policy refuses the constructs that would let a template escape
 * the sandbox, and the limits in {@link RenderLimits} bound what a render may cost. The same rules run at write time
 * and at load time, so a row inserted directly with SQL is still refused.
 *
 * <p>Nothing here ever echoes a body, an address or a variable value into a message: a failure names the part and the
 * construct, never the content.
 */
public final class TemplatePolicy {

    /** The command contract's subject limit. */
    public static final int SUBJECT_LIMIT = 998;

    private static final String PARTIAL = "{{>";
    private static final String SET_DELIMITERS = "{{=";
    private static final String UNESCAPED_TRIPLE = "{{{";
    private static final String UNESCAPED_AMPERSAND = "{{&";

    private TemplatePolicy() {}

    /** Validates one whole submission: subject, html and the optional text part. */
    public static void validateSubmission(String subject, String html, String text, RenderLimits limits) {
        validateSubject(subject, limits);
        validatePart("html", html, limits, false);
        if (text != null) {
            validatePart("text", text, limits, true);
        }
    }

    public static void validateSubject(String subject, RenderLimits limits) {
        if (subject == null || subject.isBlank()) {
            throw new BadRequestException("a template needs a subject");
        }
        if (subject.contains("\r") || subject.contains("\n")) {
            throw new BadRequestException("the subject may not contain a line break");
        }
        if (subject.length() > SUBJECT_LIMIT) {
            throw new BadRequestException("the subject must be at most " + SUBJECT_LIMIT + " characters");
        }
        if (utf8Length(subject) > limits.maxPartBytes()) {
            throw new BadRequestException("the subject is larger than " + limits.maxPartBytes() + " bytes");
        }
        checkConstructs("subject", subject, false);
        checkDepth("subject", subject, limits.maxSectionDepth());
    }

    /**
     * @param allowUnescaped whether {@code {{{ }}} and {@code {{& }}} are permitted; only the text part allows them,
     *     because text is not markup
     */
    public static void validatePart(String part, String value, RenderLimits limits, boolean allowUnescaped) {
        if (value == null) {
            return;
        }
        if (utf8Length(value) > limits.maxPartBytes()) {
            throw new BadRequestException("the " + part + " part is larger than " + limits.maxPartBytes() + " bytes");
        }
        checkConstructs(part, value, allowUnescaped);
        checkDepth(part, value, limits.maxSectionDepth());
    }

    /** Refuses the constructs the sandbox does not implement: partials, delimiter changes and unescaped output. */
    public static void checkConstructs(String part, String value, boolean allowUnescaped) {
        if (value.contains(PARTIAL)) {
            throw new BadRequestException("the " + part + " part may not use a partial");
        }
        if (value.contains(SET_DELIMITERS)) {
            throw new BadRequestException("the " + part + " part may not change the delimiters");
        }
        if (!allowUnescaped && (value.contains(UNESCAPED_TRIPLE) || value.contains(UNESCAPED_AMPERSAND))) {
            throw new BadRequestException("the " + part + " part may not use unescaped output");
        }
    }

    public static void checkDepth(String part, String value, int maxDepth) {
        int depth = sectionDepth(value);
        if (depth > maxDepth) {
            throw new BadRequestException("the " + part + " part nests sections deeper than " + maxDepth);
        }
    }

    /**
     * The deepest section nesting a template uses, found by scanning section openers and closers. This is a bound
     * check, not a parser: a template that cannot be balanced is still bounded by the depth it declares.
     */
    public static int sectionDepth(String template) {
        int depth = 0;
        int max = 0;
        int cursor = 0;
        while (cursor < template.length()) {
            int open = template.indexOf("{{", cursor);
            if (open < 0) {
                break;
            }
            int close = template.indexOf("}}", open + 2);
            if (close < 0) {
                break;
            }
            String tag = template.substring(open + 2, close).strip();
            if (tag.startsWith("#") || tag.startsWith("^")) {
                depth++;
                max = Math.max(max, depth);
            } else if (tag.startsWith("/")) {
                depth = Math.max(0, depth - 1);
            }
            cursor = close + 2;
        }
        return max;
    }

    private static int utf8Length(String value) {
        return value.getBytes(StandardCharsets.UTF_8).length;
    }
}
