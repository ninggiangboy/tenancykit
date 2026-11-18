plugins {
    `java-library`
    id("java-conventions")
}

// The Feign plumbing every service shares when it calls another service: one JSON codec, one bounded retryer, one
// token-aware client, one contract and one error-mapper base, so a service declares only its paths and its refusals.
dependencies {
    api(project(":core-kernel"))
    // A service implements ErrorDecoder and catches RetryableException, so feign-core is part of this module's API.
    // The starter and the HTTP/2 client stay implementation details: a consumer only builds a client through
    // FeignClients.
    api(libs.feign.core)
    implementation(platform(libs.spring.cloud.bom))
    implementation(libs.spring.cloud.starter.openfeign)
    implementation(libs.feign.java11)
    implementation("tools.jackson.core:jackson-databind")
    testImplementation("org.assertj:assertj-core")
    testImplementation(libs.archunit.junit5)
}
