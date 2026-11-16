plugins {
    id("layered-module-conventions")
}

// The webhook context: endpoints, deliveries, the event-type catalog and the claim and deliver use cases. The mechanics
// are shared from core-webhook; the tables and the delivery state stay here, shaped like Billing's and never shared.
dependencies {
    "adapterImplementation"("org.springframework.boot:spring-boot-starter-jooq")
    "adapterImplementation"(project(":libs:jooq-schema"))
    "adapterImplementation"("org.springframework.boot:spring-boot-starter-webmvc")
    "adapterImplementation"("org.springframework.boot:spring-boot-starter-jackson")
    // The domain names the retry schedule and the URL rule, so it sees the shared module too.
    "domainImplementation"("dev.ngb:core-webhook")
    "applicationImplementation"("dev.ngb:core-webhook")
    "adapterImplementation"("dev.ngb:core-webhook")
    // The canonical envelope the broker also sends, so the AMQP and webhook documents cannot drift apart.
    "adapterImplementation"("dev.ngb:core-messaging")
    "adapterImplementation"("org.apache.httpcomponents.client5:httpclient5")
    testImplementation("org.assertj:assertj-core")
}
