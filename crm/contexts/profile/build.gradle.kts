plugins {
    id("layered-module-conventions")
}

// The profile context: one customer read as one document. It is a read model only — it owns no table and writes
// nothing — and it composes the slices it shows through the api packages of the contexts that own them, which the
// architecture rules enforce.
dependencies {
    "applicationImplementation"(project(":contexts:customers"))
    "applicationImplementation"(project(":contexts:orders"))
    "applicationImplementation"(project(":contexts:activity"))
    "adapterImplementation"(project(":contexts:customers"))
    "adapterImplementation"(project(":contexts:orders"))
    "adapterImplementation"(project(":contexts:activity"))
    // A malformed slice size answers with the shared problem body; the web layer comes from the starter.
    "adapterImplementation"("dev.ngb:core-spring")
    "adapterImplementation"("org.springframework.boot:spring-boot-starter-webmvc")
    "adapterImplementation"("org.springframework.boot:spring-boot-starter-jackson")
    testImplementation("org.assertj:assertj-core")
}
