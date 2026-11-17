plugins {
    id("layered-module-conventions")
}

// The orders context: the Tenant's orders with their lines, the returns recorded against them, and the two events an
// order change announces. It resolves and creates a customer only through the customers context's api package, which
// is the one part of that context it names.
dependencies {
    "applicationImplementation"(project(":contexts:customers"))
    "adapterImplementation"(project(":contexts:customers"))
    "adapterImplementation"("org.springframework.boot:spring-boot-starter-jooq")
    // The problem body a malformed cursor answers with, and the web layer's argument handling.
    "adapterImplementation"("dev.ngb:core-spring")
    "adapterImplementation"(project(":libs:jooq-schema"))
    "adapterImplementation"("org.springframework.boot:spring-boot-starter-webmvc")
    "adapterImplementation"("org.springframework.boot:spring-boot-starter-jackson")
    testImplementation("org.assertj:assertj-core")
}
