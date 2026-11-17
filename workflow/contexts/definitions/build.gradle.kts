plugins {
    id("layered-module-conventions")
}

// The definitions context: the graph language, the draft, the immutable versions, the checker and the simulation.
dependencies {
    // The replica tables are read through jOOQ; the application layer never sees a generated type.
    "adapterImplementation"("org.springframework.boot:spring-boot-starter-jooq")
    // The list-query compiler and the argument resolver the read adapters need.
    "adapterImplementation"("dev.ngb:core-spring")
    "adapterImplementation"(project(":libs:jooq-schema"))
    "adapterImplementation"("org.springframework.boot:spring-boot-starter-webmvc")
    "adapterImplementation"("org.springframework.boot:spring-boot-starter-jackson")
    "adapterImplementation"("dev.ngb:core-replica")
    testImplementation("org.assertj:assertj-core")
}
