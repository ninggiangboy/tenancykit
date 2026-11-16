plugins {
    id("layered-module-conventions")
}

// The points context: the Tenant's members, the orders and returns it records, the append-only ledger and the lots a
// balance is summed from. It reads the program's rules through that context's api package and nothing else of it.
dependencies {
    // The earn engine judges a line against the program's currency unit, its tier multipliers and its promotions, so the
    // domain sees the program's published views.
    "domainImplementation"(project(":contexts:program"))
    "applicationImplementation"(project(":contexts:program"))
    "adapterImplementation"("org.springframework.boot:spring-boot-starter-jooq")
    // The list-query compiler the ledger read needs.
    "adapterImplementation"("dev.ngb:core-spring")
    "adapterImplementation"(project(":libs:jooq-schema"))
    "adapterImplementation"("org.springframework.boot:spring-boot-starter-webmvc")
    "adapterImplementation"("org.springframework.boot:spring-boot-starter-jackson")
    testImplementation("org.assertj:assertj-core")
}
