plugins {
    id("layered-module-conventions")
}

// The activity context: anything the Tenant's own systems report that a customer did. The type is the writer's free
// string, so a new source posts a new type without a change here. It resolves a customer only through the customers
// context's api package, which is the one part of that context this one names.
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
