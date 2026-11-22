plugins {
    id("layered-module-conventions")
}

dependencies {
    // The transport adapters. Only this context sees them; the port names no transport type.
    "adapterImplementation"("org.springframework.boot:spring-boot-starter-jooq")
    "adapterImplementation"("org.springframework.boot:spring-boot-starter-mail")
    "adapterImplementation"("org.springframework.amqp:spring-rabbit")
    // The inbound handler contract lives in the shared messaging infrastructure.
    "adapterImplementation"("dev.ngb:core-messaging")
    "adapterImplementation"(project(":libs:jooq-schema"))
    // The command contract is validated at the boundary, before anything is written.
    "adapterImplementation"(libs.json.schema.validator)
    // SESv2 and STS are adapter-only, exactly as Stripe is scoped inside Billing's payment context.
    "adapterImplementation"(platform(libs.aws.sdk.bom))
    "adapterImplementation"(libs.aws.sesv2)
    "adapterImplementation"(libs.aws.sts)
    testImplementation("org.assertj:assertj-core")
}
