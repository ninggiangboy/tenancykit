package dev.ngb.attribution.links.domain.campaign;

/**
 * How a conversion's value is divided among the clicks in its chain. The model is chosen per campaign and the vectors
 * in {@code contracts/attribution/attribution-vectors/} are its test.
 */
public enum AttributionModel {
    /** All credit to the first click inside the window. */
    FIRST,
    /** All credit to the last click inside the window. */
    LAST,
    /** Split evenly, with the largest-remainder residual on the last touch. */
    LINEAR
}
