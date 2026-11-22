plugins {
    `java-library`
}

// Keycloak runs on the JDK its image ships (21); the jar is compiled for that release whatever JDK builds it.
val keycloakVersion = "26.8.2"

repositories { mavenCentral() }


tasks.withType<JavaCompile>().configureEach { options.release = 21 }

dependencies {
    // Provided by the server at run time, never bundled.
    compileOnly("org.keycloak:keycloak-core:$keycloakVersion")
    compileOnly("org.keycloak:keycloak-server-spi:$keycloakVersion")
    compileOnly("org.keycloak:keycloak-server-spi-private:$keycloakVersion")
    compileOnly("org.keycloak:keycloak-services:$keycloakVersion")
    compileOnly("jakarta.ws.rs:jakarta.ws.rs-api:4.0.0")

    // The one library the server does not ship: the mailer sender publishes to the broker. It is bundled into the jar
    // (see `tasks.jar`); slf4j is left out because the server already provides it, and netty because the blocking
    // connection this uses never loads it.
    implementation("com.rabbitmq:amqp-client:5.31.0") {
        exclude(group = "org.slf4j")
        exclude(group = "io.netty")
    }

    // Only the classes the tests touch: the server's own dependency tree is Quarkus, which a test of this size must
    // not pull in, so these are taken without their transitive dependencies.
    for (module in listOf("keycloak-core", "keycloak-common", "keycloak-server-spi", "keycloak-server-spi-private", "keycloak-services")) {
        testImplementation("org.keycloak:$module:$keycloakVersion") { isTransitive = false }
    }
    testImplementation("com.fasterxml.jackson.core:jackson-databind:2.22.4")
    testImplementation("com.fasterxml.jackson.core:jackson-core:2.22.4")
    testImplementation("com.fasterxml.jackson.core:jackson-annotations:2.22")
    testImplementation("com.fasterxml.jackson.datatype:jackson-datatype-jdk8:2.22.4")
    testImplementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310:2.22.4")
    testImplementation("jakarta.ws.rs:jakarta.ws.rs-api:4.0.0")
    // The command is checked against the schema the mailer publishes, so the provider cannot drift from it.
    testImplementation("com.networknt:json-schema-validator:3.0.7")
    testImplementation("tools.jackson.core:jackson-databind:3.1.7")
    testImplementation(platform("org.junit:junit-bom:5.13.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
    // The contract test reads the schema the whole suite shares, from the repository root.
    systemProperty("email.send.schema", rootDir.resolve("../../../contracts/commands/email.send.schema.json").canonicalPath)
}

tasks.jar {
    archiveFileName = "suite-keycloak-provider.jar"
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    from({
        configurations.runtimeClasspath.get().filter { it.name.endsWith(".jar") }.map { zipTree(it) }
    }) {
        exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA", "META-INF/MANIFEST.MF", "module-info.class")
    }
}
