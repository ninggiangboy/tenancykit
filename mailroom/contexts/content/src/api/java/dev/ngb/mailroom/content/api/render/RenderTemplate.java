package dev.ngb.mailroom.content.api.render;

import dev.ngb.mailroom.shared.TemplateKey;
import java.util.Map;
import java.util.UUID;

/**
 * The content context's contract for the sending context: resolve a template and render it.
 *
 * <p>Rendering is strict at every depth. A missing variable, a forbidden construct or a broken limit is a hard
 * failure, never an empty substitution: a half-filled email must not go out. The caller records the failure and does
 * not send.
 */
public interface RenderTemplate {

    /** The pointer a campaign pins: the template's id and its current revision. */
    TemplateSummary resolve(TemplateKey key);

    /** Renders the template's current revision. */
    RenderedEmail render(TemplateKey key, Map<String, Object> variables);

    /** Renders the exact revision a campaign or an email pinned, regardless of later edits. */
    RenderedEmail renderPinned(UUID templateId, int revision, Map<String, Object> variables);

    /** Renders a body the caller supplied directly, under the same sandbox limits a stored template obeys. */
    RenderedEmail renderInline(String subject, String html, String text, Map<String, Object> variables);
}
