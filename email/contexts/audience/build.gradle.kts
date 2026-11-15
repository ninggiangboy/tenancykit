plugins {
    id("layered-module-conventions")
}

dependencies {
    // Every context persists through jOOQ; the generated types stay in the adapter layer.
    "adapterImplementation"("org.springframework.boot:spring-boot-starter-jooq")
    "adapterImplementation"("org.springframework.boot:spring-boot-starter-webmvc")
    "adapterImplementation"(project(":libs:jooq-schema"))
    // The attributes and filter columns are JSONB; the adapter serialises them with the suite's mapper.
    "adapterImplementation"("tools.jackson.core:jackson-databind")
    testImplementation("org.assertj:assertj-core")
}
