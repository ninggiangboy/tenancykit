plugins {
    id("layered-module-conventions")
}

dependencies {
    // A task group's members must be active users, which the platform replica seam answers.
    "applicationImplementation"(project(":contexts:platform"))
    "adapterImplementation"("org.springframework.boot:spring-boot-starter-webmvc")
    "adapterImplementation"("org.springframework.boot:spring-boot-starter-jackson")
    "adapterImplementation"("dev.ngb:core-spring")
    // The replica tables are read through jOOQ; the application layer never sees a generated type.
    "adapterImplementation"("org.springframework.boot:spring-boot-starter-jooq")
    "adapterImplementation"(project(":libs:jooq-schema"))
    "adapterImplementation"("dev.ngb:core-replica")
    testImplementation("org.assertj:assertj-core")
}
