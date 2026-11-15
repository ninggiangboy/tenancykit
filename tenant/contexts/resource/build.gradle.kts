plugins {
    id("layered-module-conventions")
}

dependencies {
    "applicationImplementation"(project(":contexts:billing-client"))
    "applicationImplementation"(project(":contexts:customer"))
    "applicationImplementation"(project(":contexts:catalog"))
    "adapterImplementation"(project(":contexts:billing-client"))
    "adapterImplementation"(project(":contexts:customer"))
    "adapterImplementation"(project(":contexts:catalog"))
    "adapterImplementation"(project(":contexts:identity"))
    "adapterImplementation"("org.springframework.boot:spring-boot-starter-webmvc")
    "adapterImplementation"("org.springframework.security:spring-security-core")
    testImplementation("org.assertj:assertj-core")
    "adapterImplementation"("org.springframework.boot:spring-boot-starter-jooq")
    "adapterImplementation"(project(":libs:jooq-schema"))
}
