package dev.ngb.survey.authoring.api.document;

import java.util.Objects;

/**
 * One reason a draft or a version is not acceptable, by JSON pointer so a builder can point at the member.
 *
 * <p>The message never carries an answer, an address or a token: a validation failure is echoed to the tenant that
 * sent the document, and the pointer already names the offending member.
 *
 * @param pointer an RFC 6901 JSON pointer such as {@code /pages/0/questions/1/type}
 * @param code the closed reason, see {@link ValidationCode}
 * @param message a human-readable explanation with no client data
 */
public record ValidationError(String pointer, ValidationCode code, String message) {

    public ValidationError {
        Objects.requireNonNull(pointer, "pointer");
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(message, "message");
    }
}
