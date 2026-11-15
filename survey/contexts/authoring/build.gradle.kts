plugins {
    id("layered-module-conventions")
}

// The authoring context: surveys, drafts, immutable versions, translations and the expression engine with the flow
// evaluator. collection and insight reach it only through its api package.
dependencies {
    "adapterImplementation"("org.springframework.boot:spring-boot-starter-jooq")
    "adapterImplementation"(project(":libs:jooq-schema"))
    "adapterImplementation"("org.springframework.boot:spring-boot-starter-jackson")
    testImplementation("org.assertj:assertj-core")
}
