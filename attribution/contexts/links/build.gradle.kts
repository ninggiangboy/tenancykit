plugins {
    id("layered-module-conventions")
}

dependencies {
    // Every context persists through jOOQ and serves its own endpoints; the generated types stay in the adapter layer.
    "adapterImplementation"("org.springframework.boot:spring-boot-starter-jooq")
    "adapterImplementation"("org.springframework.boot:spring-boot-starter-webmvc")
    "adapterImplementation"(project(":libs:jooq-schema"))
    "adapterImplementation"("dev.ngb:core-replica")
    // A destination URL is refused when it points at a private address; that policy is the shared core's, so the
    // domain reuses it instead of copying a CIDR list. The module is plain Java with no framework.
    "domainImplementation"("dev.ngb:core-webhook")
    // A destination's match names the device class the shared visitor classifier returns.
    "domainImplementation"("dev.ngb:core-visitor")
    "applicationImplementation"("dev.ngb:core-visitor")
    "adapterImplementation"("dev.ngb:core-visitor")
    // A QR code is an SVG or a PNG of one string.
    "adapterImplementation"(libs.zxing.core)
    "adapterImplementation"(libs.zxing.javase)
    // BCrypt is the suite's existing password encoder; a link's password is stored only as its hash.
    "adapterImplementation"("org.springframework.security:spring-security-crypto")
    testImplementation(libs.zxing.javase)
    testImplementation("dev.ngb:core-webhook")
    testImplementation("dev.ngb:core-visitor")
    testImplementation("org.assertj:assertj-core")
}
