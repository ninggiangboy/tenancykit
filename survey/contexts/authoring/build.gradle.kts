plugins {
    id("layered-module-conventions")
}

// The authoring context: surveys, drafts, immutable versions, translations and the expression engine with the flow
// evaluator. collection and insight reach it only through its api package.
dependencies {
    "adapterImplementation"("org.springframework.boot:spring-boot-starter-jooq")
    // The list-query compiler and the argument resolver the read adapters need.
    "adapterImplementation"("dev.ngb:core-spring")
    "adapterImplementation"("org.springframework.boot:spring-boot-starter-webmvc")
    "adapterImplementation"(project(":libs:jooq-schema"))
    "adapterImplementation"("org.springframework.boot:spring-boot-starter-jackson")
    // A draft is validated against the packaged version contract before it is stored.
    "adapterImplementation"(libs.json.schema.validator)
    testImplementation("org.assertj:assertj-core")
}
