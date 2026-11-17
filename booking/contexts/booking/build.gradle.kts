plugins {
    id("layered-module-conventions")
}

// The booking aggregate claims through the inventory port and reads the offering and session rules through the
// catalog's api package.
dependencies {
    "applicationImplementation"(project(":contexts:catalog"))
    "applicationImplementation"(project(":contexts:inventory"))
    "adapterImplementation"("org.springframework.boot:spring-boot-starter-jooq")
    "adapterImplementation"("org.springframework.boot:spring-boot-starter-webmvc")
    "adapterImplementation"("org.springframework.boot:spring-boot-starter-security-oauth2-resource-server")
    "adapterImplementation"("dev.ngb:core-spring")
    "adapterImplementation"(project(":libs:jooq-schema"))
    testImplementation("org.assertj:assertj-core")
}
