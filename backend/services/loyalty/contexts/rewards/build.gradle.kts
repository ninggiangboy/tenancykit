plugins {
    id("layered-module-conventions")
}

// The rewards context: the Tenant's rewards catalog and the single-use vouchers a redemption issues. It spends a member's
// points through the points context's api and nothing else of it, and implements the reversal hook that context declares.
dependencies {
    // A redemption spends through the one spend function the points context publishes, and a voucher read resolves the
    // member's reference through its api too.
    "applicationImplementation"(project(":contexts:points"))
    "adapterImplementation"("org.springframework.boot:spring-boot-starter-jooq")
    // The list-query compiler the catalog and voucher reads need.
    "adapterImplementation"("dev.ngb:core-spring")
    "adapterImplementation"(project(":libs:jooq-schema"))
    "adapterImplementation"("org.springframework.boot:spring-boot-starter-webmvc")
    "adapterImplementation"("org.springframework.boot:spring-boot-starter-jackson")
    testImplementation("org.assertj:assertj-core")
}
