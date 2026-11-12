plugins {
    `java-library`
    `java-test-fixtures`
    id("java-conventions")
}

// One library every app uses to keep local replicas of Platform data current from platform.events, rebuild them
// from the snapshot API, resolve the tenant from the hostname, turn a verified token into an Actor and reject
// tenants that have not enabled the app.
dependencies {
    api(project(":core-kernel"))
    api(project(":core-messaging"))
    api("org.jooq:jooq")
    // The resource server and the web client that fetches the snapshot.
    api("org.springframework.boot:spring-boot-starter-security")
    api("org.springframework.boot:spring-boot-starter-security-oauth2-resource-server")
    implementation("org.springframework:spring-context")
    implementation("org.springframework:spring-web")
    implementation("org.springframework:spring-tx")
    implementation("org.springframework:spring-jdbc")
    implementation("org.springframework.amqp:spring-rabbit")
    implementation("org.springframework.boot:spring-boot")
    implementation("org.springframework.boot:spring-boot-autoconfigure")
    compileOnly("jakarta.servlet:jakarta.servlet-api")
    implementation("org.slf4j:slf4j-api")
    implementation("tools.jackson.core:jackson-databind")
    implementation("com.nimbusds:nimbus-jose-jwt")
    testImplementation("org.assertj:assertj-core")
    testImplementation("org.springframework:spring-jdbc")
    testImplementation("org.testcontainers:testcontainers-postgresql")
    testImplementation("org.testcontainers:testcontainers-rabbitmq")
    testImplementation("org.postgresql:postgresql")
    testImplementation(libs.archunit.junit5)
    // The contract test runs the shipped reference DDL through the published schema assertion.
    testImplementation(testFixtures(project(":core-replica")))

    // The schema assertion fixture is a production-shaped helper other apps import; it needs the BOM too.
    testFixturesImplementation(platform("org.springframework.boot:spring-boot-dependencies:4.1.2"))
    testFixturesImplementation("org.assertj:assertj-core")
}
