plugins {
    id("layered-module-conventions")
}

dependencies {
    // Every context persists through jOOQ and serves its own endpoints; the generated types stay in the adapter layer.
    "adapterImplementation"("org.springframework.boot:spring-boot-starter-jooq")
    // The jOOQ compiler of the list-query contract, so a listing is one binding and one fetch.
    "adapterImplementation"("dev.ngb:core-spring")
    "adapterImplementation"("org.springframework.boot:spring-boot-starter-webmvc")
    "adapterImplementation"(project(":libs:jooq-schema"))
    "adapterImplementation"("dev.ngb:core-replica")
    testImplementation("org.assertj:assertj-core")
}
