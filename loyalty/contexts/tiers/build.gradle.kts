plugins {
    id("layered-module-conventions")
}

// The tiers context: the evaluation over the spend each order's earn record holds. It keeps no table of its own, reads
// the member and its earn records through the points context's api and the tier list through the program's, so the
// context graph stays acyclic.
dependencies {
    // The policy compares a spend against the program's published tier thresholds.
    "domainImplementation"(project(":contexts:program"))
    "applicationImplementation"(project(":contexts:program"))
    "applicationImplementation"(project(":contexts:points"))
}
