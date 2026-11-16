plugins {
    id("layered-module-conventions")
}

dependencies {
    // The wallet, the charge context the discount seam is asked about, and the credit path a wallet-credit coupon
    // redeems through. Coupon depends on money; money names only its own port, so the arrow never turns back.
    "applicationImplementation"(project(":contexts:money"))
    "adapterImplementation"(project(":contexts:money"))
    // The customer lookup and its visibility rule, shared with the money path.
    "applicationImplementation"(project(":contexts:customer"))
    "adapterImplementation"(project(":contexts:customer"))
    "adapterImplementation"("org.springframework.boot:spring-boot-starter-jooq")
    "adapterImplementation"(project(":libs:jooq-schema"))
    "adapterImplementation"("org.springframework.boot:spring-boot-starter-webmvc")
    testImplementation("org.assertj:assertj-core")
}
