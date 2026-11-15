plugins {
    id("layered-module-conventions")
}

// The webhook context: endpoints, deliveries, the event-type catalog and the claim and deliver use cases. The mechanics
// are shared from core-webhook; the tables and the delivery state stay here.
dependencies {
    "adapterImplementation"("org.springframework.boot:spring-boot-starter-jooq")
    "adapterImplementation"(project(":libs:jooq-schema"))
    "adapterImplementation"("org.springframework.boot:spring-boot-starter-webmvc")
    "adapterImplementation"("org.springframework.boot:spring-boot-starter-jackson")
    "applicationImplementation"("dev.ngb:core-webhook")
    "adapterImplementation"("dev.ngb:core-webhook")
    "adapterImplementation"("dev.ngb:core-messaging")
    testImplementation("org.assertj:assertj-core")
}
