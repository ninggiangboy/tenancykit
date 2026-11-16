plugins {
    id("layered-module-conventions")
}

// The catalog reads and writes its own tables through jOOQ; the application layer still sees only its ports.
dependencies {
    "adapterImplementation"("org.springframework.boot:spring-boot-starter-jooq")
    "adapterImplementation"("org.springframework.boot:spring-boot-starter-webmvc")
    "adapterImplementation"("org.springframework.boot:spring-boot-starter-security-oauth2-resource-server")
    "adapterImplementation"("dev.ngb:core-spring")
    "adapterImplementation"(project(":libs:jooq-schema"))
    testImplementation("org.assertj:assertj-core")
}
