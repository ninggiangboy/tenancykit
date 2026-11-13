plugins {
    id("layered-module-conventions")
}

dependencies {
    // The platform contract (scope, actor, permissions, audit, shared errors).
    "adapterImplementation"("org.springframework.boot:spring-boot-starter-jooq")
    "adapterImplementation"(project(":libs:jooq-schema"))
    "adapterImplementation"("org.springframework.boot:spring-boot-starter-webmvc")
    "adapterImplementation"("org.springframework.boot:spring-boot-starter-jackson")
    // The canonical envelope the broker also sends, so the AMQP and webhook documents cannot drift apart.
    "adapterImplementation"("dev.ngb:core-messaging")
    "adapterImplementation"("org.apache.httpcomponents.client5:httpclient5")
    testImplementation("org.assertj:assertj-core")
}
