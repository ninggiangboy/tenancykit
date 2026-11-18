plugins {
    id("layered-module-conventions")
}

dependencies {
    "adapterImplementation"("dev.ngb:core-feign")
    // The API interface declares its paths with Spring MVC annotations, so the adapter compiles against spring-web.
    "adapterImplementation"("org.springframework:spring-web")
    "adapterImplementation"("org.springframework.boot:spring-boot-starter-jackson")
    testImplementation("org.assertj:assertj-core")
}
