plugins {
    id("layered-module-conventions")
}

dependencies {
    // An app call must address the Tenant by its own hostname, which the platform context's replica seam answers.
    "applicationImplementation"(project(":contexts:platform"))
    // The guards and the DNS resolver are shared; the policy of what may be reached stays in this context.
    "adapterImplementation"("dev.ngb:core-webhook")
    // The outbound door sets its own methods and reads a capped body; the shared sender is POST-only.
    "adapterImplementation"("org.apache.httpcomponents.client5:httpclient5")
    "adapterImplementation"("org.springframework.boot:spring-boot-starter-jooq")
    "adapterImplementation"("org.springframework.boot:spring-boot-starter-jackson")
    "adapterImplementation"("org.springframework.boot:spring-boot-starter-webmvc")
    "adapterImplementation"(project(":libs:jooq-schema"))
    "adapterImplementation"("dev.ngb:core-spring")
    "adapterImplementation"("dev.ngb:core-replica")
    testImplementation("org.assertj:assertj-core")
}
