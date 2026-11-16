plugins {
    id("layered-module-conventions")
}

dependencies {
    // Every context persists through jOOQ and serves its own endpoints; the generated types stay in the adapter layer.
    "adapterImplementation"("org.springframework.boot:spring-boot-starter-jooq")
    "adapterImplementation"("org.springframework.boot:spring-boot-starter-webmvc")
    "adapterImplementation"(project(":libs:jooq-schema"))
    "adapterImplementation"("dev.ngb:core-replica")
    // The chain and the owning campaign's policy are reached through tracking's and links' api packages, which is the
    // one way a context uses another; the engine itself stays plain Java.
    "applicationImplementation"(project(":contexts:tracking"))
    "applicationImplementation"(project(":contexts:links"))
    // The erasure endpoint reaches the conversion store through tracking's api, and this adapter is what answers it.
    "adapterImplementation"(project(":contexts:tracking"))
    testImplementation("org.assertj:assertj-core")
    // The vector runner reads the committed attribution vectors, never a copy of them.
    testImplementation("tools.jackson.core:jackson-databind")
}
