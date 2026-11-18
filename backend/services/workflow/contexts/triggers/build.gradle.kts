plugins {
    id("layered-module-conventions")
}

dependencies {
    // A trigger resolves a definition's newest published version and starts an instance through the runtime's api.
    "domainImplementation"(project(":contexts:definitions"))
    "applicationImplementation"(project(":contexts:definitions"))
    "applicationImplementation"(project(":contexts:runtime"))
    "adapterImplementation"(project(":contexts:definitions"))
    "adapterImplementation"(project(":contexts:runtime"))
    // The trigger, event and schedule tables are read and written through jOOQ.
    "adapterImplementation"("org.springframework.boot:spring-boot-starter-jooq")
    "adapterImplementation"(project(":libs:jooq-schema"))
    "adapterImplementation"("dev.ngb:core-spring")
    "adapterImplementation"("dev.ngb:core-replica")
    "adapterImplementation"("org.springframework.boot:spring-boot-starter-webmvc")
    "adapterImplementation"("org.springframework.boot:spring-boot-starter-jackson")
    "adapterImplementation"("org.springframework.boot:spring-boot-starter-amqp")
    testImplementation("org.assertj:assertj-core")
}
