plugins {
    `java-library`
    id("java-conventions")
}

// The Spring implementation of the runtime ports and the jOOQ-backed audit log. Only this module may see a
// framework type.
dependencies {
    api(project(":core-kernel"))
    api(project(":core-query"))
    api("org.springframework:spring-tx")
    api("org.jooq:jooq")
    // The permission interceptor is the only web type core offers; an app that has no web layer never loads it.
    compileOnly("org.springframework:spring-webmvc")
    compileOnly("jakarta.servlet:jakarta.servlet-api")
    testImplementation("org.assertj:assertj-core")
    testImplementation("org.springframework:spring-web")
    testImplementation("org.springframework:spring-webmvc")
    testImplementation("jakarta.servlet:jakarta.servlet-api")
    testImplementation("org.springframework:spring-test")
    // jOOQ's Settings class carries JAXB annotations; without them the test compile warns, and warnings are errors.
    testImplementation("jakarta.xml.bind:jakarta.xml.bind-api")
    testImplementation(libs.archunit.junit5)
}
