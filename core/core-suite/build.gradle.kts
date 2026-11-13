plugins {
    `java-library`
    id("java-conventions")
}

// What a request must satisfy to be a member of the suite: the two-issuer token decoder, the hostname's tenant
// scope, the actor the token names and the app entitlement. It reads the replica and never fetches it.
dependencies {
    api(project(":core-replica"))
    api("org.springframework.boot:spring-boot-starter-security")
    api("org.springframework.boot:spring-boot-starter-security-oauth2-resource-server")
    implementation("org.springframework:spring-web")
    implementation("com.nimbusds:nimbus-jose-jwt")
    compileOnly("jakarta.servlet:jakarta.servlet-api")
    testImplementation(project(":core-spring"))
    testImplementation("org.springframework:spring-jdbc")
    testImplementation("org.assertj:assertj-core")
    testImplementation("org.testcontainers:testcontainers-postgresql")
    testImplementation("org.postgresql:postgresql")
    testImplementation(libs.archunit.junit5)
}
