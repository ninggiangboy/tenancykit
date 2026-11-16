plugins {
    id("java-conventions")
    `java-library`
}

// shared-core is plain Java: main depends on nothing but the JDK and the shared core kernel.
dependencies {
    // Every layer that sees shared-core sees the kernel too, so a port never has to be re-exported by hand.
    api("dev.ngb:core-kernel")
    // A listing declares its filter schema in the application layer and its binding in the adapter, so both see the
    // list-query contract without naming the Spring implementation.
    api("dev.ngb:core-query")
    testImplementation("org.assertj:assertj-core")
}
