plugins {
    id("layered-module-conventions")
}

// The public edge: opaque customer tokens, the guard filter, the per-instance limits and the public surface, which is a
// thin layer over booking's use cases and the catalog/inventory reads.
dependencies {
    "applicationImplementation"(project(":contexts:booking"))
    "applicationImplementation"(project(":contexts:catalog"))
    "applicationImplementation"(project(":contexts:inventory"))
    "adapterImplementation"("org.springframework.boot:spring-boot-starter-jooq")
    "adapterImplementation"("org.springframework.boot:spring-boot-starter-webmvc")
    "adapterImplementation"("org.springframework.boot:spring-boot-starter-security-oauth2-resource-server")
    "adapterImplementation"("dev.ngb:core-spring")
    "adapterImplementation"("dev.ngb:core-suite")
    "adapterImplementation"("dev.ngb:core-visitor")
    // The public controller is a thin route over the other contexts' application use cases.
    "adapterImplementation"(project(":contexts:booking"))
    "adapterImplementation"(project(":contexts:catalog"))
    "adapterImplementation"(project(":contexts:inventory"))
    "adapterImplementation"(project(":libs:jooq-schema"))
    testImplementation("org.assertj:assertj-core")
}
