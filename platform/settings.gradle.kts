pluginManagement {
    includeBuild("build-logic")
}

includeBuild("../core")

dependencyResolutionManagement {
    repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
    repositories { mavenCentral() }
}

rootProject.name = "platform"

include("libs:shared-kernel", "libs:jooq-schema", "contexts:tenancy", "apps:app-boot", "testing:arch-fixtures")
