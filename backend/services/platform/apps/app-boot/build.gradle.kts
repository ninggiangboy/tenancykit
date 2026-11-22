plugins {
    id("boot-conventions")
}

dependencies {
    implementation(platform(libs.spring.modulith.bom))
    implementation(project(":libs:shared-kernel"))
    implementation("dev.ngb:core-spring")
    // Documents every @Listing operation from the schema it names.
    implementation("dev.ngb:core-openapi")
    implementation("dev.ngb:core-messaging")
    // The snapshot API serves the replica's streams, so the producer names the one enum that lists them; the replica
    // writer the tests use to prove the published events converge proves it through the same dependency.
    implementation("dev.ngb:core-replica")
    implementation(project(":contexts:tenancy"))
    implementation(project(":contexts:access"))
    implementation(project(":contexts:integration"))
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
    testImplementation(testFixtures("dev.ngb:core-openapi"))
    // The shared architecture rule set every service runs, so the three services cannot drift apart.
    testImplementation("dev.ngb:core-arch")
    // The core rules cover every shared module, including the one Platform does not call with.
    testImplementation("dev.ngb:core-feign")
    // Generates the jOOQ schema from the migrations for the drift test.
    testImplementation("org.jooq:jooq-codegen")
    testImplementation("org.jooq:jooq-meta")
    testImplementation(libs.archunit.junit5)
    testImplementation("org.assertj:assertj-core")
    testImplementation("org.junit.jupiter:junit-jupiter-params")
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.testcontainers:testcontainers-junit-jupiter")
    testImplementation("org.testcontainers:testcontainers-postgresql")
    testImplementation("org.testcontainers:testcontainers-rabbitmq")
}

// The permission catalogs are the suite's one list of app codes and permission codes.
tasks.processResources {
    from(layout.projectDirectory.dir("../../../../../contracts/permissions")) {
        include("*.yaml")
        into("permissions")
    }
}

tasks.test {
    // `./gradlew :apps:app-boot:test -Dopenapi.update=true` rewrites contracts/openapi/platform.yaml.
    systemProperty("openapi.update", System.getProperty("openapi.update", "false"))
    // `./gradlew :apps:app-boot:test --tests '*JooqSchemaTest' -Djooq.update=true` regenerates the committed jOOQ schema.
    systemProperty("jooq.update", System.getProperty("jooq.update", "false"))
}
