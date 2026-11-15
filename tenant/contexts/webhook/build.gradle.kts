plugins {
    id("layered-module-conventions")
}

dependencies {
    "applicationImplementation"(project(":contexts:resource"))
    "adapterImplementation"(project(":contexts:resource"))
    "adapterImplementation"("org.springframework.boot:spring-boot-starter-jackson")
    "adapterImplementation"("org.springframework.boot:spring-boot-starter-webmvc")
    testImplementation("org.assertj:assertj-core")
    "adapterImplementation"("org.springframework.boot:spring-boot-starter-jooq")
    "adapterImplementation"(project(":libs:jooq-schema"))
}
