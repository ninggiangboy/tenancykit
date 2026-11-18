plugins {
    id("layered-module-conventions")
}

dependencies {
    // Every context persists through jOOQ; the generated types stay in the adapter layer.
    "adapterImplementation"("org.springframework.boot:spring-boot-starter-jooq")
    "adapterImplementation"("dev.ngb:core-spring")
    "adapterImplementation"("org.springframework.boot:spring-boot-starter-webmvc")
    "adapterImplementation"(project(":libs:jooq-schema"))
    // The renderer is JMustache, wrapped in this module's own sandbox; the policy is deliberately not shared with
    // notification, whose policy forbids sections.
    "adapterImplementation"(libs.jmustache)
    testImplementation("org.assertj:assertj-core")
}
