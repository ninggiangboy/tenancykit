plugins {
    id("layered-module-conventions")
}

// The insight context: aggregates, cross-tabs, trends, the funnel, respondents and exports. It reads authoring and
// collection through their api packages and writes only its own exports table.
dependencies {
    "adapterImplementation"("org.springframework.boot:spring-boot-starter-jooq")
    "adapterImplementation"(project(":libs:jooq-schema"))
    "adapterImplementation"("org.springframework.boot:spring-boot-starter-jackson")
    "adapterImplementation"(project(":contexts:authoring"))
    "adapterImplementation"(project(":contexts:collection"))
    testImplementation("org.assertj:assertj-core")
}
