plugins {
    id("layered-module-conventions")
}

dependencies {
    // `sending` reaches templates and the audience only through their api packages.
    "applicationImplementation"(project(":contexts:content"))
    "applicationImplementation"(project(":contexts:audience"))
    "adapterImplementation"(project(":contexts:content"))
    "adapterImplementation"(project(":contexts:audience"))
    "adapterImplementation"("org.springframework.boot:spring-boot-starter-jooq")
    "adapterImplementation"("dev.ngb:core-spring")
    "adapterImplementation"("org.springframework.boot:spring-boot-starter-webmvc")
    "adapterImplementation"(project(":libs:jooq-schema"))
    // The result consumer implements the suite's inbound handler contract.
    "adapterImplementation"("dev.ngb:core-messaging")
    // The producer validates its own command against the packaged contract before the outbox row exists.
    "adapterImplementation"(libs.json.schema.validator)
    "adapterImplementation"("tools.jackson.core:jackson-databind")
    testImplementation("org.assertj:assertj-core")
}
