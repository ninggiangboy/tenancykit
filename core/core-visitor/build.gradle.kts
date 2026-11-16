plugins {
    `java-library`
    id("java-conventions")
}

// The bounded, non-identifying facts the suite is allowed to store about a request: the device class, the browser
// family, the traffic class, the referrer's registrable domain, the country and a daily visitor hash. It is plain
// Java with no Spring, so the redirect path and a worker can both use it.
dependencies {
    api(project(":core-kernel"))
    // The public-suffix list decides a referrer's registrable domain; it handles example.co.uk and example.com.vn.
    implementation("org.apache.httpcomponents.client5:httpclient5")
    // The DB-IP Lite country database is read with MaxMind's reader; the file is fetched, never committed.
    implementation(libs.maxmind.db)
    testImplementation("org.assertj:assertj-core")
    testImplementation(libs.archunit.junit5)
}
