plugins {
    id("layered-module-conventions")
}

dependencies {
    "adapterImplementation"("org.springframework.boot:spring-boot-starter-jooq")
    "adapterImplementation"(project(":libs:jooq-schema"))
    "adapterImplementation"("org.springframework.boot:spring-boot-starter-webmvc")
    "adapterImplementation"("org.springframework.boot:spring-boot-starter-jackson")
    // The token endpoint reads the caller's address for the per-caller rate limit; the container provides it at runtime.
    "adapterCompileOnly"("jakarta.servlet:jakarta.servlet-api")
    testImplementation("org.assertj:assertj-core")
}
