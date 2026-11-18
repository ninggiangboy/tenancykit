package dev.ngb.core.visitor;

/**
 * Whether a request is a person or a machine.
 *
 * <p>{@link #BOT} is a crawler, a link-preview fetcher or an empty user agent; {@link #SUSPECT} is a visitor that
 * exceeded the per-link click rate. Both still receive their answer — a link preview must render — and both are
 * excluded from a default report and never credited.
 */
public enum TrafficClass {
    HUMAN,
    BOT,
    SUSPECT
}
