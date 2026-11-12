plugins {
    `java-library`
    id("java-conventions")
}

// The jOOQ-backed shared infrastructure every service needs: the outbox, the inbox and the audit log.
// Tables are addressed with plain SQL names (see MessagingTables), because a service's generated schema
// belongs to that service and never to core.
dependencies {
    api(project(":core-spring"))
    api("org.jooq:jooq")
    api("io.micrometer:micrometer-core")
    api("org.springframework.amqp:spring-rabbit")
    implementation("org.springframework:spring-context")
    // AmqpInboundDispatcher's constructor takes a RetryTemplate, so every consumer must see the type.
    api(libs.spring.retry)
    implementation("tools.jackson.core:jackson-databind")
    implementation("org.slf4j:slf4j-api")
    testImplementation(libs.archunit.junit5)
    testImplementation("org.assertj:assertj-core")
    testImplementation("org.springframework:spring-jdbc")
    testImplementation("org.testcontainers:testcontainers-postgresql")
    testImplementation("org.testcontainers:testcontainers-rabbitmq")
    testImplementation("org.postgresql:postgresql")
}
