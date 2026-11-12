plugins {
    id("layered-module-conventions")
}

dependencies {
    // Tenancy through its api only: provisioning creates a Tenant and seeds its first administrator.
    "applicationImplementation"(project(":contexts:tenancy"))
    "adapterImplementation"("org.springframework.boot:spring-boot-starter-jooq")
    "adapterImplementation"(project(":libs:jooq-schema"))
    "adapterImplementation"("org.springframework.boot:spring-boot-starter-webmvc")
    "adapterImplementation"("org.springframework.boot:spring-boot-starter-jackson")
    "adapterImplementation"("tools.jackson.dataformat:jackson-dataformat-yaml")
    testImplementation("org.assertj:assertj-core")
}
