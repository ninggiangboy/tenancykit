plugins {
    id("layered-module-conventions")
}

dependencies {
    // The replica's read seam: the catalog and the audience resolver read Platform data only through this api.
    "applicationImplementation"(project(":contexts:platform"))
    "adapterImplementation"(project(":contexts:platform"))
    "adapterImplementation"("org.springframework.boot:spring-boot-starter-jooq")
    "adapterImplementation"("org.springframework.boot:spring-boot-starter-webmvc")
    "adapterImplementation"(project(":libs:jooq-schema"))
    // The renderer is an adapter detail; the application layer names no template engine.
    "adapterImplementation"(libs.jmustache)
    // The email command validates itself against the packaged contract before the outbox row exists.
    "adapterImplementation"(libs.json.schema.validator)
    "adapterImplementation"("tools.jackson.core:jackson-databind")
    testImplementation("org.assertj:assertj-core")
}
