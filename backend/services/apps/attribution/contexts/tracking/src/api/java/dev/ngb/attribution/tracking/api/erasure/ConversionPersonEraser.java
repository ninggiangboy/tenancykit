package dev.ngb.attribution.tracking.api.erasure;

/**
 * Removes the person from a visitor's conversions, which the attribution context owns and the tracking context's
 * erasure endpoint calls through this api.
 *
 * <p>A conversion keeps its amount, its currency and its credits; what it loses is the visitor id and the click. That
 * keeps a report's revenue steady after an erasure, which is the plan's rule.
 */
@FunctionalInterface
public interface ConversionPersonEraser {

    /**
     * @param visitorId the consented visitor id whose conversions lose their person
     * @return how many conversions were changed
     */
    int erase(String visitorId);

    /** No conversions exist to erase, for a deployment or a test where the attribution context is absent. */
    static ConversionPersonEraser none() {
        return visitorId -> 0;
    }
}
