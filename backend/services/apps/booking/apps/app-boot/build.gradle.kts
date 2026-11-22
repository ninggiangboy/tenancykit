plugins {
    id("boot-conventions")
}

dependencies {
    implementation(platform(libs.spring.modulith.bom))
    implementation(project(":libs:shared-core"))
    implementation(project(":contexts:platform"))
    implementation(project(":contexts:catalog"))
    implementation(project(":contexts:inventory"))
    implementation(project(":contexts:booking"))
    implementation(project(":contexts:gate"))
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
    testImplementation(libs.json.schema.validator)
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

// The permission catalog and the event schemas are packaged so the catalog test and the webhook payload tests read the
// real files, the same way every app does.
tasks.processResources {
    from(layout.projectDirectory.dir("../../../../../../contracts/permissions")) {
        include("*.yaml")
        into("permissions")
    }
    from(layout.projectDirectory.dir("../../../../../../contracts/booking")) {
        include("**/*.json")
        into("booking")
    }
    from(layout.projectDirectory.dir("../../../../../../contracts/events/booking")) {
        include("*.schema.json")
        into("events")
    }
}

// A plain HTTP runner for the flash-sale hold path; it has no framework or database access of its own.
val loadTest = sourceSets.create("loadTest")
tasks.register<JavaExec>("loadTest") {
    group = "verification"
    description = "Runs the booking hold load scenario against BOOKING_LOAD_BASE_URL"
    classpath = loadTest.runtimeClasspath
    mainClass.set("dev.ngb.booking.load.BookingLoadTest")
    environment(System.getenv())
}

tasks.test {
    // `./gradlew :apps:app-boot:test --tests '*JooqSchemaTest' -Djooq.update=true` regenerates the committed jOOQ schema.
    systemProperty("jooq.update", System.getProperty("jooq.update", "false"))
    systemProperty("openapi.update", System.getProperty("openapi.update", "false"))
}
