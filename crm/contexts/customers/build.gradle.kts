plugins {
    id("layered-module-conventions")
}

// The customers context: the Tenant's own customers, their batch upsert, the lookup a single customer is read
// through, and the announcement of a creation. Its api package is what the other contexts of crm may call; nothing
// else of the context is visible to them.
dependencies {
    "adapterImplementation"("org.springframework.boot:spring-boot-starter-jooq")
    // The list-query compiler and the argument resolver the read adapter and the controller need.
    "adapterImplementation"("dev.ngb:core-spring")
    "adapterImplementation"(project(":libs:jooq-schema"))
    "adapterImplementation"("org.springframework.boot:spring-boot-starter-webmvc")
    "adapterImplementation"("org.springframework.boot:spring-boot-starter-jackson")
    testImplementation("org.assertj:assertj-core")
}
