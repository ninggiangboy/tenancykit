plugins {
    id("boot-conventions")
}

dependencies {
    implementation(platform(libs.spring.modulith.bom))
    implementation(project(":libs:shared-core"))
    implementation(project(":contexts:platform"))
    implementation(project(":contexts:program"))
    implementation(project(":contexts:points"))
    implementation(project(":contexts:tiers"))
    implementation(project(":contexts:rewards"))
    implementation(project(":contexts:webhook"))
    implementation("dev.ngb:core-spring")
    implementation("dev.ngb:core-openapi")
    implementation("dev.ngb:core-messaging")
    implementation("dev.ngb:core-replica")
    implementation("dev.ngb:core-platform-sync")
    implementation("dev.ngb:core-suite")
    implementation("dev.ngb:core-feign")
    implementation("dev.ngb:core-webhook")
    implementation("org.springframework.boot:spring-boot-starter")
    implementation("org.springframework.boot:spring-boot-starter-jackson")
    implementation("org.springframework.boot:spring-boot-starter-jooq")
    implementation(project(":libs:jooq-schema"))
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-amqp")
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-security-oauth2-resource-server")
    implementation(libs.springdoc.webmvc)
    // The Scalar reference at /scalar, rendered from the same /v3/api-docs document.
    implementation(libs.springdoc.scalar)
    // Pinned above the version springdoc manages so /scalar serves the current Scalar UI.
    implementation(libs.scalar.core)
    implementation(libs.scalar.webmvc)
    implementation("org.springframework.boot:spring-boot-starter-flyway")
    implementation("org.flywaydb:flyway-database-postgresql")
    runtimeOnly("org.postgresql:postgresql")

    testImplementation(project(":testing:arch-fixtures"))
    // The shared architecture rule set every service runs, so the services cannot drift apart.
    testImplementation("dev.ngb:core-arch")
    // Generates the jOOQ schema from the migrations for the drift test.
    testImplementation("org.jooq:jooq-codegen")
    testImplementation("org.jooq:jooq-meta")
    testImplementation(libs.archunit.junit5)
    testImplementation(libs.spring.modulith.core)
    testImplementation(libs.spring.modulith.docs)
    testImplementation("tools.jackson.dataformat:jackson-dataformat-yaml")
    testImplementation("org.assertj:assertj-core")
    testImplementation("org.junit.jupiter:junit-jupiter-params")
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.testcontainers:testcontainers-junit-jupiter")
    testImplementation("org.testcontainers:testcontainers-postgresql")
    testImplementation("org.postgresql:postgresql")
    testImplementation("org.testcontainers:testcontainers-rabbitmq")
    testImplementation(project(":libs:shared-core"))
}

// The permission catalog and the twelve event schemas are packaged for the app itself, not for a test: the contract
// test reads the real catalog file, and the webhook event-type catalog is the schemas in contracts/events/loyalty, so a
// subscription to a type the app does not send cannot be created. A schema added there needs no code change.
tasks.processResources {
    from(layout.projectDirectory.dir("../../../../../../contracts/permissions")) {
        // Every catalog, because the shared caller-label reader resolves any code the app documents, not only its own.
        include("*.yaml")
        into("permissions")
    }
    from(layout.projectDirectory.dir("../../../../../../contracts/events/loyalty")) {
        include("*.schema.json")
        into("events")
    }
}

tasks.test {
    // `./gradlew :apps:app-boot:test --tests '*JooqSchemaTest' -Djooq.update=true` regenerates the committed jOOQ schema.
    systemProperty("jooq.update", System.getProperty("jooq.update", "false"))
}
