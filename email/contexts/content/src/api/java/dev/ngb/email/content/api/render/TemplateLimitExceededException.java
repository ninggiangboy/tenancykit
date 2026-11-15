package dev.ngb.email.content.api.render;

/** A render broke a sandbox limit: output size, iteration count or wall clock. */
public class TemplateLimitExceededException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public TemplateLimitExceededException(String limit) {
        super("the render exceeded the " + limit + " limit");
    }
}
