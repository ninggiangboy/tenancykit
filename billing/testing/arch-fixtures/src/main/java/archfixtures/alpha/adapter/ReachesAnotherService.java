package archfixtures.alpha.adapter;

import dev.ngb.platform.fixture.PlatformThing;

/** Violates no-dependency-on-another-service: an application class names the Platform service's code. */
public class ReachesAnotherService {
    PlatformThing platform;
}
