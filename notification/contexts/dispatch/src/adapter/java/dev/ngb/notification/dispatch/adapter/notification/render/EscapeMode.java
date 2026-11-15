package dev.ngb.notification.dispatch.adapter.notification.render;

/**
 * Escaping differs per part: an HTML body escapes, a plain-text body must not ({@code &amp;} in a text body is a bug).
 * One renderer with a mode per part, not two renderers.
 */
public enum EscapeMode {

    HTML(true),
    NONE(false);

    private final boolean escape;

    EscapeMode(boolean escape) {
        this.escape = escape;
    }

    public boolean escapes() {
        return escape;
    }
}
