plugins {
    id("layered-module-conventions")
}

dependencies {
    // The guards and the DNS resolver are shared; the policy of what may be reached stays in this context.
    "adapterImplementation"("dev.ngb:core-webhook")
    // The outbound door sets its own methods and reads a capped body; the shared sender is POST-only.
    "adapterImplementation"("org.apache.httpcomponents.client5:httpclient5")
    "adapterImplementation"("org.springframework.boot:spring-boot-starter-jooq")
    "adapterImplementation"("org.springframework.boot:spring-boot-starter-jackson")
    "adapterImplementation"(project(":libs:jooq-schema"))
    "adapterImplementation"("dev.ngb:core-spring")
    "adapterImplementation"("dev.ngb:core-replica")
    testImplementation("org.assertj:assertj-core")
}
