plugins {
    id("layered-module-conventions")
}

dependencies {
    // The platform contract: scope, actor, permissions, idempotency, audit and the shared error types.
    "adapterImplementation"("org.springframework.boot:spring-boot-starter-jooq")
    "adapterImplementation"(project(":libs:jooq-schema"))
    "adapterImplementation"("org.springframework.boot:spring-boot-starter-webmvc")
    testImplementation("org.assertj:assertj-core")
}
