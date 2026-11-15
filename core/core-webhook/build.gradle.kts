plugins {
    `java-library`
    id("java-conventions")
}

// The signed-webhook mechanics every app that delivers one shares: signing, the destination policy, the guarded
// resolver, the HTTP sender and the retry schedule. Endpoints, deliveries, their tables and their event catalog stay
// in the app, so one app's outbound state is never read by another.
dependencies {
    api(project(":core-kernel"))
    // No core-webhook type in the public API names an HttpClient 5 type: the sender's constructor and records take
    // only JDK types, and the guarded resolver is an implementation detail a caller never constructs. Keeping the
    // dependency internal is what lets a domain layer depend on this module without seeing a transport library.
    implementation("org.apache.httpcomponents.client5:httpclient5")
    testImplementation(libs.archunit.junit5)
    testImplementation("org.assertj:assertj-core")
}
