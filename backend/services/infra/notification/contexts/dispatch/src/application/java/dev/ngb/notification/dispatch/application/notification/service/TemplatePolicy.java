package dev.ngb.notification.dispatch.application.notification.service;

import dev.ngb.notification.dispatch.application.notification.exception.ForbiddenTemplateConstructException;
import dev.ngb.notification.dispatch.application.notification.exception.TemplateTooLargeException;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * The rules a template part must satisfy, in one place so the renderer and the write API cannot drift.
 *
 * <p>Templates are logic-less by enforcement: a section, an inverted section, a partial, a closing tag or an unescaped
 * interpolation is refused at write time and again at load time, so a row inserted directly with SQL cannot smuggle
 * logic in. Size limits bound memory per render and keep a payload inside the email contract's maxima.
 */
public final class TemplatePolicy {

    /** The email contract's subject limit. */
    public static final int SUBJECT_LIMIT = 998;

    public static final List<String> FORBIDDEN = List.of("{{#", "{{^", "{{>", "{{/", "{{&", "{{{");

    private TemplatePolicy() {}

    public static void rejectForbidden(String part) {
        for (String construct : FORBIDDEN) {
            if (part.contains(construct)) {
                throw new ForbiddenTemplateConstructException(construct);
            }
        }
    }

    /** Checks every part of a variant, including the subject-specific limit and its newline ban. */
    public static void validatePart(String partName, String part, int maxPartBytes) {
        rejectForbidden(part);
        int bytes = part.getBytes(StandardCharsets.UTF_8).length;
        if (bytes > maxPartBytes) {
            throw new TemplateTooLargeException(partName, bytes, maxPartBytes);
        }
        if ("email.subject".equals(partName)) {
            if (part.contains("\n") || part.contains("\r")) {
                throw new dev.ngb.core.error.BadRequestException("the subject may not contain a line break");
            }
            if (part.length() > SUBJECT_LIMIT) {
                throw new TemplateTooLargeException(partName, part.length(), SUBJECT_LIMIT);
            }
        }
    }
}
