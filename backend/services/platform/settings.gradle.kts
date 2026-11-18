pluginManagement {
    includeBuild("build-logic")
}

includeBuild("../../libs/core")

dependencyResolutionManagement {
    repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
    repositories { mavenCentral() }
}

rootProject.name = "platform"

include("libs:shared-kernel", "libs:jooq-schema", "contexts:tenancy", "contexts:access", "contexts:integration", "apps:app-boot", "testing:arch-fixtures")
