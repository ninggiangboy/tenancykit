plugins {
    `java-library`
    id("java-conventions")
}

// The list-query contract: the whitelist schema, the parser, the cursor and the page types. Plain Java on top of the
// kernel, so every layer of every service may name it; the jOOQ compiler and the Spring wiring live in core-spring.
dependencies {
    api(project(":core-kernel"))
    testImplementation("org.assertj:assertj-core")
    testImplementation(libs.archunit.junit5)
}
