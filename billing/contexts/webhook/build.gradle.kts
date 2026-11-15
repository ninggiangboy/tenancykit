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
    // Signing, the destination policy, the guarded resolver, the HTTP send and the retry schedule are shared with
    // every app that delivers a webhook; tables and delivery state stay here. Domain and application name the shared
    // types (the header set, the URL rule, the retry schedule), so they see the module too.
    "domainImplementation"("dev.ngb:core-webhook")
    "applicationImplementation"("dev.ngb:core-webhook")
    "adapterImplementation"("dev.ngb:core-webhook")
    "adapterImplementation"("org.apache.httpcomponents.client5:httpclient5")
    testImplementation("org.assertj:assertj-core")
}
