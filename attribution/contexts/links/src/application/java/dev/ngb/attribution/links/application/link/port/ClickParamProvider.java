package dev.ngb.attribution.links.application.link.port;

/**
 * The query parameter the redirect uses to carry a click's identity. A destination that already defines it would
 * collide with the parameter the redirect appends, so a link refuses such a destination at save time.
 */
public interface ClickParamProvider {

    String current();
}
