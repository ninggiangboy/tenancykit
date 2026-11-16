plugins {
    id("layered-module-conventions")
}

// The collection context: links and visits, drafts and submissions, the typed answers rows, uploads, the response cap
// and deletion. It reaches authoring only through authoring's api package.
dependencies {
    "adapterImplementation"("org.springframework.boot:spring-boot-starter-jooq")
    "adapterImplementation"("org.springframework.boot:spring-boot-starter-webmvc")
    "adapterImplementation"(project(":libs:jooq-schema"))
    "adapterImplementation"("org.springframework.boot:spring-boot-starter-jackson")
    // The `api` packages only: a link's creation asks authoring whether its survey is open, and a submit runs
    // authoring's flow. Nothing of authoring's domain or adapter is visible from here.
    "applicationImplementation"(project(":contexts:authoring"))
    "adapterImplementation"(project(":contexts:authoring"))
    // A visit keeps only the bounded, non-identifying facts the shared visitor library produces.
    "applicationImplementation"("dev.ngb:core-visitor")
    "adapterImplementation"("dev.ngb:core-visitor")
    testImplementation("org.assertj:assertj-core")
}
