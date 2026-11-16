plugins {
    id("layered-module-conventions")
}

// The insight context: aggregates, cross-tabs, trends, the funnel, respondents and exports. It reads authoring and
// collection through their api packages and writes only its own exports table.
dependencies {
    "adapterImplementation"("org.springframework.boot:spring-boot-starter-jooq")
    "adapterImplementation"("org.springframework.boot:spring-boot-starter-webmvc")
    "adapterImplementation"(project(":libs:jooq-schema"))
    "adapterImplementation"("org.springframework.boot:spring-boot-starter-jackson")
    // The `api` packages only: a version's question types come from authoring, and the responses it reports on come
    // from collection. Nothing of either context's domain or adapter is visible from here.
    "applicationImplementation"(project(":contexts:authoring"))
    "applicationImplementation"(project(":contexts:collection"))
    "adapterImplementation"(project(":contexts:authoring"))
    "adapterImplementation"(project(":contexts:collection"))
    // An export's CSV lands in the same object store an upload uses.
    "adapterImplementation"(platform(libs.aws.sdk.bom))
    "adapterImplementation"(libs.aws.s3)
    testImplementation("org.assertj:assertj-core")
}
