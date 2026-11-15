package dev.ngb.notification.dispatch.domain.template;

import dev.ngb.notification.shared.TemplateKey;

/**
 * One message rendered from a template variant.
 *
 * @param templateVersion the shared override's own revision, or 0 for the classpath default; the dispatch ledger
 *     records it, so "which content produced this email" is answerable after the template changes again
 */
public record RenderedMessage(
        TemplateKey templateKey,
        int templateVersion,
        String locale,
        String title,
        String body,
        String subject,
        String html,
        String text) {}
