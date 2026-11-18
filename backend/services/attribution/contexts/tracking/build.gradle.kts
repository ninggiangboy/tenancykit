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
    // The click event carries the classified device and traffic classes; the api and adapter both name them, so a click
    // and its stored row agree without a translation layer.
    "apiImplementation"("dev.ngb:core-visitor")
    "adapterImplementation"("dev.ngb:core-visitor")
    // A batch published to the ingest exchange is a bounded buffer drained by one flusher; the cache and the rate
    // buckets live beside it. Both are adapter details.
    "adapterImplementation"(libs.caffeine)
    "adapterImplementation"("dev.ngb:core-messaging")
    testImplementation("dev.ngb:core-visitor")
    testImplementation("org.assertj:assertj-core")
    // The click payload is asserted against the committed ingest schema, not against a copy of it.
    testImplementation(libs.json.schema.validator)
    testImplementation("tools.jackson.core:jackson-databind")
    testImplementation("io.micrometer:micrometer-core")
}
