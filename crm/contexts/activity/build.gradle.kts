plugins {
    id("layered-module-conventions")
}

// The context exists with its four layered source sets and its api package; the domain, application and adapter
// layers hold code from the phase that builds the feature. Nothing outside the context may name it except through
// its api package, which the architecture rules enforce.
dependencies {
    testImplementation("org.assertj:assertj-core")
}
