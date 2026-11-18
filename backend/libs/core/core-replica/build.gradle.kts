plugins {
    `java-library`
    `java-test-fixtures`
    id("java-conventions")
}

// The app's own copy of the Platform data it enforces: the five tables addressed with plain SQL, the guarded upsert
// every snapshot row and event goes through, the platform.events handler and the reference DDL its migration must
// satisfy. It holds no HTTP client, no resource server and no request filter; those live in core-platform-sync and
// core-suite.
dependencies {
    api(project(":core-kernel"))
    api(project(":core-messaging"))
    api("org.jooq:jooq")
    implementation("org.slf4j:slf4j-api")
    testImplementation("org.assertj:assertj-core")
    testImplementation("org.testcontainers:testcontainers-postgresql")
    testImplementation("org.postgresql:postgresql")
    testImplementation(libs.archunit.junit5)
    // The contract test runs the shipped reference DDL through the published schema assertion.
    testImplementation(testFixtures(project(":core-replica")))

    // The schema assertion fixture is a production-shaped helper other apps import; it needs the BOM too.
    testFixturesImplementation(platform("org.springframework.boot:spring-boot-dependencies:4.1.2"))
    testFixturesImplementation("org.assertj:assertj-core")
}
