plugins {
    id("layered-module-conventions")
}

dependencies {
    // The pinned version's graph and the expression language come from the definitions context, through its api only.
    "domainImplementation"(project(":contexts:definitions"))
    "applicationImplementation"(project(":contexts:definitions"))
    "adapterImplementation"(project(":contexts:definitions"))
    // The replica tables are read through jOOQ; the application layer never sees a generated type.
    "adapterImplementation"("org.springframework.boot:spring-boot-starter-jooq")
    "adapterImplementation"(project(":libs:jooq-schema"))
    "adapterImplementation"("org.springframework.boot:spring-boot-starter-jackson")
    "adapterImplementation"("dev.ngb:core-replica")
    testImplementation("org.assertj:assertj-core")
}
