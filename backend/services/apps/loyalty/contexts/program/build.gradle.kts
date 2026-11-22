plugins {
    id("layered-module-conventions")
}

// The program context: the Tenant's one points program, its ordered tier list and its promotions. Its api package is the
// only door another context has onto those tables.
dependencies {
    "adapterImplementation"("org.springframework.boot:spring-boot-starter-jooq")
    // The list-query compiler and the argument resolver the read adapters need.
    "adapterImplementation"("dev.ngb:core-spring")
    "adapterImplementation"(project(":libs:jooq-schema"))
    "adapterImplementation"("org.springframework.boot:spring-boot-starter-webmvc")
    "adapterImplementation"("org.springframework.boot:spring-boot-starter-jackson")
    testImplementation("org.assertj:assertj-core")
}
