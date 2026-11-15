plugins {
    id("boot-conventions")
}

dependencies {
    implementation(platform(libs.spring.modulith.bom))
    implementation(project(":libs:shared-core"))
    implementation(project(":contexts:platform"))
    implementation(project(":contexts:content"))
    implementation(project(":contexts:audience"))
    implementation(project(":contexts:sending"))
    implementation("dev.ngb:core-spring")
    implementation("dev.ngb:core-messaging")
    implementation("dev.ngb:core-replica")
    implementation("dev.ngb:core-platform-sync")
    implementation("dev.ngb:core-suite")
    implementation("dev.ngb:core-feign")
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

// The command schema under contracts/commands is the source of truth for what a producer may send; the
// application reads it from the classpath, so the validator and the contract cannot drift apart.
//
// The permission catalogs and the event schema are packaged for the same reason: the catalog test reads the real
// files and the result consumer can name the event type without a literal.
tasks.processResources {
    from(layout.projectDirectory.dir("../../../contracts/commands")) {
        include("*.schema.json")
        into("commands")
    }
    from(layout.projectDirectory.dir("../../../contracts/permissions")) {
        include("*.yaml")
        into("permissions")
    }
    from(layout.projectDirectory.dir("../../../contracts/events/mailer")) {
        include("*.schema.json")
        into("events")
    }
}

tasks.test {
    // `./gradlew :apps:app-boot:test --tests '*JooqSchemaTest' -Djooq.update=true` regenerates the committed jOOQ schema.
    systemProperty("jooq.update", System.getProperty("jooq.update", "false"))
    systemProperty("openapi.update", System.getProperty("openapi.update", "false"))
}
