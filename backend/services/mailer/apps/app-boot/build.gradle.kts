plugins {
    id("boot-conventions")
}

dependencies {
    implementation(platform(libs.spring.modulith.bom))
    implementation(project(":libs:shared-core"))
    implementation(project(":contexts:email"))
    implementation("dev.ngb:core-spring")
    implementation("dev.ngb:core-messaging")
    implementation("org.springframework.boot:spring-boot-starter")
    implementation("org.springframework.boot:spring-boot-starter-jackson")
    implementation("org.springframework.boot:spring-boot-starter-jooq")
    implementation(project(":libs:jooq-schema"))
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    // The worker serves no business endpoint, but its health probe is an HTTP call on the server port, so it needs a
    // web server for the actuator to answer on.
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.springframework.boot:spring-boot-starter-amqp")
    // The wiring class selects between the two senders, so it needs JavaMailSender on its own compile classpath.
    implementation("org.springframework.boot:spring-boot-starter-mail")
    implementation("org.springframework.boot:spring-boot-starter-flyway")
    implementation("org.flywaydb:flyway-database-postgresql")
    runtimeOnly("org.postgresql:postgresql")

    testImplementation(project(":testing:arch-fixtures"))
    // The shared architecture rule set every service runs, so the four services cannot drift apart.
    testImplementation("dev.ngb:core-arch")
    // Generates the jOOQ schema from the migrations for the drift test.
    testImplementation("org.jooq:jooq-codegen")
    testImplementation("org.jooq:jooq-meta")
    testImplementation(libs.archunit.junit5)
    testImplementation(libs.spring.modulith.core)
    testImplementation(libs.spring.modulith.docs)
    testImplementation(libs.json.schema.validator)
    // The SES adapter's tests build requests and exceptions, so the SDK is needed at test compile time; the module's
    // adapter dependencies do not leak onto a consumer's compile classpath.
    testImplementation(platform(libs.aws.sdk.bom))
    testImplementation(libs.aws.sesv2)
    testImplementation("org.assertj:assertj-core")
    testImplementation("org.junit.jupiter:junit-jupiter-params")
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.testcontainers:testcontainers-junit-jupiter")
    testImplementation("org.testcontainers:testcontainers-postgresql")
    testImplementation("org.testcontainers:testcontainers-rabbitmq")
}

// The command schemas under contracts/commands are the source of truth for what a producer may send; the
// application reads them from the classpath, so the validator and the contract cannot drift apart.
tasks.processResources {
    from(layout.projectDirectory.dir("../../../contracts/commands")) {
        include("*.schema.json")
        into("commands")
    }
}

tasks.test {
    // `./gradlew :apps:app-boot:test --tests '*JooqSchemaTest' -Djooq.update=true` regenerates the committed jOOQ schema.
    systemProperty("jooq.update", System.getProperty("jooq.update", "false"))
    // The AWS Emulator smoke test needs a running emulator, so it is opt-in and never part of build.
    useJUnitPlatform { excludeTags("emulator") }
}

tasks.register<Test>("emulatorTest") {
    group = "verification"
    description = "Runs the AWS Emulator smoke test; needs the emulator on 127.0.0.1:9297. Never part of build."
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform { includeTags("emulator") }
    outputs.upToDateWhen { false }
}
