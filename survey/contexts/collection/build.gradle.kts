plugins {
    id("layered-module-conventions")
}

// The collection context: links and visits, drafts and submissions, the typed answers rows, uploads, the response cap
// and deletion. It reaches authoring only through authoring's api package.
dependencies {
    "adapterImplementation"("org.springframework.boot:spring-boot-starter-jooq")
    // The list-query compiler and the argument resolver the read adapters need.
    "adapterImplementation"("dev.ngb:core-spring")
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
    // The presigned upload and its magic-byte check run over the same AWS SDK major the mailer uses for SES.
    "adapterImplementation"(platform(libs.aws.sdk.bom))
    "adapterImplementation"(libs.aws.s3)
    testImplementation("org.assertj:assertj-core")
}
