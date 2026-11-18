plugins {
    id("layered-module-conventions")
}

dependencies {
    "applicationImplementation"(project(":contexts:billing-client"))
    "applicationImplementation"(project(":contexts:resource"))
    "applicationImplementation"(project(":contexts:usage"))
    testImplementation(project(":contexts:billing-client"))
    testImplementation(project(":contexts:resource"))
    testImplementation(project(":contexts:usage"))
    testImplementation("org.assertj:assertj-core")
}
