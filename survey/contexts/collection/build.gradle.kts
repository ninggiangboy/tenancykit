plugins {
    id("layered-module-conventions")
}

// The collection context: links and visits, drafts and submissions, the typed answers rows, uploads, the response cap
// and deletion. It reaches authoring only through authoring's api package.
dependencies {
    "adapterImplementation"("org.springframework.boot:spring-boot-starter-jooq")
    "adapterImplementation"(project(":libs:jooq-schema"))
    "adapterImplementation"("org.springframework.boot:spring-boot-starter-jackson")
    "adapterImplementation"(project(":contexts:authoring"))
    testImplementation("org.assertj:assertj-core")
}
