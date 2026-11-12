plugins {
    `java-library`
    id("java-conventions")
}

// The Spring implementation of the runtime ports. Only this module may see a framework type.
dependencies {
    api(project(":core-kernel"))
    api("org.springframework:spring-tx")
    // The permission interceptor is the only web type core offers; an app that has no web layer never loads it.
    compileOnly("org.springframework:spring-webmvc")
    compileOnly("jakarta.servlet:jakarta.servlet-api")
    testImplementation("org.assertj:assertj-core")
}
