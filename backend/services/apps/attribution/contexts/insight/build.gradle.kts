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
    // The rollup reads traffic and credits through tracking's and attribution's api packages, and the cost report reads
    // spend and campaign currency through theirs. The insight context never names another context's tables.
    "applicationImplementation"(project(":contexts:tracking"))
    "applicationImplementation"(project(":contexts:attribution"))
    "applicationImplementation"(project(":contexts:spend"))
    "applicationImplementation"(project(":contexts:links"))
    "adapterImplementation"(project(":contexts:tracking"))
    "adapterImplementation"(project(":contexts:attribution"))
    "adapterImplementation"(project(":contexts:spend"))
    "adapterImplementation"(project(":contexts:links"))
    testImplementation("org.assertj:assertj-core")
}
