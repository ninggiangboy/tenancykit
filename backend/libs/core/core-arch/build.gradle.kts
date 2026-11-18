plugins {
    `java-library`
    id("java-conventions")
}

// The architecture rule set every service runs, parameterised by that service's profile, so one rule set guards
// three builds and a rule can never be quietly removed from one of them. archunit-junit5 is part of the module's
// API: a service writes its own ArchUnit tests and reports the same rule ids as every other service.
dependencies {
    api(libs.archunit.junit5)
}
