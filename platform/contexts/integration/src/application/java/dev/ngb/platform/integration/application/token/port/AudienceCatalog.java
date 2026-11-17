package dev.ngb.platform.integration.application.token.port;

/**
 * The audiences a suite token may name: exactly the apps the deployment offers, one per permission catalog.
 *
 * <p>Platform is the only issuer, so this is where "an app exists" becomes "a credential may ask for a token for it".
 * The list is not hardcoded: a suite that ships another app's catalog offers that app's audience without a code change,
 * and an app that is not shipped cannot be named in a grant.
 */
public interface AudienceCatalog {

    /** @return true when an app of this name is offered by the deployment */
    boolean offers(String audience);
}
