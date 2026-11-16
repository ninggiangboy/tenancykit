plugins {
    id("boot-conventions")
}

dependencies {
    implementation(platform(libs.spring.modulith.bom))
    implementation(project(":libs:shared-core"))
    implementation(project(":contexts:platform"))
    implementation(project(":contexts:links"))
    implementation(project(":contexts:tracking"))
    implementation(project(":contexts:attribution"))
    implementation(project(":contexts:spend"))
    implementation(project(":contexts:insight"))
    implementation("dev.ngb:core-spring")
    // Documents every @Listing operation from the schema it names, so the served contract cannot drift from the code.
    implementation("dev.ngb:core-openapi")
    implementation("dev.ngb:core-messaging")
    implementation("dev.ngb:core-replica")
    implementation("dev.ngb:core-platform-sync")
    implementation("dev.ngb:core-suite")
    implementation("dev.ngb:core-feign")
    // The redirect and the collect endpoints classify a request through the shared visitor library, and the public
    // limits and caches are Caffeine. Both are the app's own dependencies, not a context's.
    implementation("dev.ngb:core-visitor")
    implementation(libs.caffeine)
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

// The permission catalog and the OpenAPI document are read from the classpath so the tests compare the real files
// with the code, and the ingest schema is read from the classpath so the consumer and the contract cannot drift apart.
tasks.processResources {
    from(layout.projectDirectory.dir("../../../contracts/permissions")) {
        include("*.yaml")
        into("permissions")
    }
    from(layout.projectDirectory.dir("../../../contracts/attribution")) {
        include("**/*.schema.json")
        into("attribution")
    }
}

tasks.test {
    // `./gradlew :apps:app-boot:test --tests '*JooqSchemaTest' -Djooq.update=true` regenerates the committed jOOQ schema.
    systemProperty("jooq.update", System.getProperty("jooq.update", "false"))
    systemProperty("openapi.update", System.getProperty("openapi.update", "false"))
}
