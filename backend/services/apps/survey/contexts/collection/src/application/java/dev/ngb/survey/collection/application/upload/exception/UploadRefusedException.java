package dev.ngb.survey.collection.application.upload.exception;

/**
 * A presigned upload is refused: the question takes no file, the declared size or type is outside its limits, or the
 * question already holds as many files as it accepts.
 *
 * <p>Each reason answers a distinct code, and none of them echoes the filename or the declared type.
 */
public class UploadRefusedException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public enum Reason {
        /** The question is not a file question, so it accepts no upload. */
        NOT_A_FILE_QUESTION,
        /** The declared size is zero or past the question's limit. */
        SIZE_REFUSED,
        /** The declared type is not one the question allows. */
        TYPE_REFUSED,
        /** The question already holds as many files as it accepts. */
        TOO_MANY_FILES
    }

    private final transient Reason reason;

    public UploadRefusedException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
