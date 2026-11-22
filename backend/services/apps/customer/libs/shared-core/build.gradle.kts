plugins {
    id("java-conventions")
    `java-library`
}

// shared-core is plain Java: main depends on nothing but the JDK and the shared core kernel.
dependencies {
    // Every layer that sees shared-core sees the kernel too, so a port never has to be re-exported by hand.
    api("dev.ngb:core-kernel")
    // The list-query contract is plain Java and ports return its Page, so every layer sees it as it sees the kernel.
    api("dev.ngb:core-query")
    testImplementation("org.assertj:assertj-core")
}
