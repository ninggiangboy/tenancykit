package dev.ngb.survey.authoring.application.draft.exception;

import dev.ngb.survey.authoring.api.document.ValidationError;
import java.util.List;

/**
 * The document cannot be stored or published as sent. The errors ride along, because a builder needs the pointer of
 * every offending member rather than one message.
 */
public class DraftInvalidException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final transient List<ValidationError> errors;

    public DraftInvalidException(List<ValidationError> errors) {
        super("the survey document has structural errors");
        this.errors = List.copyOf(errors);
    }

    public List<ValidationError> errors() {
        return errors;
    }
}
