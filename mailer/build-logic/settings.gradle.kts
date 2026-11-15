dependencyResolutionManagement {
    repositories { mavenCentral(); gradlePluginPortal() }
    versionCatalogs { create("libs") { from(files("../gradle/libs.versions.toml")) } }
}
rootProject.name = "mailer-build-logic"
