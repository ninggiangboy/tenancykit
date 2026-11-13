plugins {
    `java-library`
    id("java-conventions")
}

// Fetches Platform's snapshot and rebuilds the replica from it: the snapshot client, the paging bootstrap and the
// connection settings. The HTTP transport itself is a port the boot layer fills in, so this module names no client.
dependencies {
    api(project(":core-replica"))
    implementation("org.springframework.boot:spring-boot")
    implementation("org.slf4j:slf4j-api")
    testImplementation(project(":core-spring"))
    testImplementation("org.springframework:spring-jdbc")
    testImplementation("org.assertj:assertj-core")
    testImplementation("org.testcontainers:testcontainers-postgresql")
    testImplementation("org.postgresql:postgresql")
    testImplementation(libs.archunit.junit5)
}
