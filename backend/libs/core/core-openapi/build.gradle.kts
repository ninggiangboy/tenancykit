plugins {
    `java-library`
    id("java-conventions")
}

// Generates the OpenAPI description of a listing from its query schema. Kept apart from core-spring so that module
// keeps seeing only the framework and jOOQ; an app without springdoc never loads this one.
dependencies {
    api(project(":core-spring"))
    api(libs.springdoc.common)
    // The caller labels and the catalog consistency check read the permission catalogs packaged by each service.
    implementation("tools.jackson.dataformat:jackson-dataformat-yaml")
    compileOnly("org.springframework:spring-webmvc")
    testImplementation("org.assertj:assertj-core")
    testImplementation(libs.archunit.junit5)
    testImplementation("org.springframework:spring-webmvc")
}
