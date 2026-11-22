package dev.ngb.mailroom.content.application.render.port;

/** The rendered parts before the caller attaches the revision it rendered. */
public record RenderedParts(String subject, String html, String text) {}
