package dev.ngb.notification.dispatch.application.notification.port;

/**
 * One row of template parts.
 *
 * <p>A shared override leaves a part null when it does not define it, and the renderer fills that part from the
 * classpath default. The classpath default has every part and a version of 0.
 */
public record TemplateVariant(String subject, String html, String text, String title, String body, int version) {

    public static TemplateVariant of(String subject, String html, String text, String title, String body) {
        return new TemplateVariant(subject, html, text, title, body, 0);
    }
}
