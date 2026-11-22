plugins {
    id("boot-conventions")
}

dependencies {
    implementation(platform(libs.spring.modulith.bom))
    implementation(project(":libs:shared-kernel"))
    implementation("dev.ngb:core-spring")
    implementation("dev.ngb:core-openapi")
    implementation("dev.ngb:core-messaging")
    implementation("dev.ngb:core-replica")
    implementation("dev.ngb:core-platform-sync")
    implementation("dev.ngb:core-suite")
    implementation("dev.ngb:core-feign")
    // The wiring builds the shared signer and HTTP sender, so boot sees the module the webhook context delegates to.
    implementation("dev.ngb:core-webhook")
    implementation(project(":contexts:customer"))
    implementation(project(":contexts:money"))
    implementation(project(":contexts:coupon"))
    implementation(project(":contexts:webhook"))
    implementation(project(":contexts:usage"))
    implementation(project(":contexts:pricing"))
    implementation(project(":contexts:payment"))
    implementation(project(":contexts:invoicing"))
    implementation(project(":contexts:session"))
    implementation("org.springframework.boot:spring-boot-starter")
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.springframework.boot:spring-boot-starter-jackson")
    implementation("org.springframework.boot:spring-boot-starter-jooq")
    implementation(project(":libs:jooq-schema"))
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-security-oauth2-resource-server")
    implementation("org.springframework.boot:spring-boot-starter-amqp")
    implementation(libs.springdoc.webmvc)
    // The Scalar reference at /scalar, rendered from the same /v3/api-docs document.
    implementation(libs.springdoc.scalar)
    // Pinned above the version springdoc manages so /scalar serves the current Scalar UI.
    implementation(libs.scalar.core)
    implementation(libs.scalar.webmvc)
    implementation("tools.jackson.dataformat:jackson-dataformat-yaml")
    implementation("org.springframework.boot:spring-boot-starter-flyway")
    implementation("org.flywaydb:flyway-database-postgresql")
    runtimeOnly("org.postgresql:postgresql")

    testImplementation(project(":testing:arch-fixtures"))
    // The shared architecture rule set every service runs, so the three services cannot drift apart.
    testImplementation("dev.ngb:core-arch")
    // Generates the jOOQ schema from the migrations for the drift test.
    testImplementation("org.jooq:jooq-codegen")
    testImplementation("org.jooq:jooq-meta")
    testImplementation(libs.archunit.junit5)
    testImplementation(libs.spring.modulith.core)
    testImplementation(libs.spring.modulith.docs)
    testImplementation(libs.json.schema.validator)
    testImplementation("org.assertj:assertj-core")
    testImplementation("org.junit.jupiter:junit-jupiter-params")
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.testcontainers:testcontainers-junit-jupiter")
    testImplementation("org.testcontainers:testcontainers-postgresql")
    testImplementation("org.testcontainers:testcontainers-rabbitmq")
}

// The event schemas under contracts/events/billing are the source of truth for which event types exist; the
// application reads them from the classpath, so the catalog and the contract cannot drift apart.
tasks.processResources {
    from(layout.projectDirectory.dir("../../../../../../contracts/events/billing")) {
        include("*.schema.json")
        into("events")
    }
    // The permission catalogs drive x-allowed-callers in the OpenAPI document, so they must be on the classpath too.
    from(layout.projectDirectory.dir("../../../../../../contracts/permissions")) {
        include("*.yaml")
        into("permissions")
    }
}

tasks.test {
    // The seeded 500-tenant run lives outside the build; `loadTest` is the only way to run it.
    useJUnitPlatform { excludeTags("loadtest") }
    // `./gradlew :apps:app-boot:test -Dopenapi.update=true` rewrites contracts/openapi/billing.yaml.
    systemProperty("openapi.update", System.getProperty("openapi.update", "false"))
    // `./gradlew :apps:app-boot:test --tests '*JooqSchemaTest' -Djooq.update=true` regenerates the committed jOOQ schema.
    systemProperty("jooq.update", System.getProperty("jooq.update", "false"))
}

tasks.register<Test>("loadTest") {
    group = "verification"
    description = "Runs the seeded webhook lag load test; never part of build."
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform { includeTags("loadtest") }
    outputs.upToDateWhen { false }
}
