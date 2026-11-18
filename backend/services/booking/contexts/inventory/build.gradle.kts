plugins {
    id("layered-module-conventions")
}

// The engines read and write their own claim tables through jOOQ, and availability reads the catalog through the
// catalog's api package only.
dependencies {
    // Assignment answers other contexts with catalog records, so the port's own signature needs the catalog's api.
    "apiImplementation"(project(":contexts:catalog"))
    // The application layer computes availability from the catalog's public calendar port.
    "applicationImplementation"(project(":contexts:catalog"))
    "adapterImplementation"("org.springframework.boot:spring-boot-starter-jooq")
    "adapterImplementation"("org.springframework.boot:spring-boot-starter-webmvc")
    "adapterImplementation"("org.springframework.boot:spring-boot-starter-security-oauth2-resource-server")
    "adapterImplementation"("dev.ngb:core-spring")
    "adapterImplementation"(project(":libs:jooq-schema"))
    "adapterImplementation"(project(":contexts:catalog"))
    testImplementation("org.assertj:assertj-core")
}
