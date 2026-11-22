plugins {
    id("layered-module-conventions")
}

dependencies {
    // The replica tables are read through jOOQ; the application layer never sees a generated type.
    "adapterImplementation"("org.springframework.boot:spring-boot-starter-jooq")
    "adapterImplementation"(project(":libs:jooq-schema"))
    "adapterImplementation"("dev.ngb:core-replica")
    testImplementation("org.assertj:assertj-core")
}
