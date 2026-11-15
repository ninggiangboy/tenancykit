pluginManagement {
    includeBuild("build-logic")
}

includeBuild("../core")

dependencyResolutionManagement {
    repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
    repositories { mavenCentral() }
}

rootProject.name = "tenant"

include(
    "libs:shared-core",
    "libs:jooq-schema",
    "contexts:identity",
    "contexts:customer",
    "contexts:catalog",
    "contexts:resource",
    "contexts:execution",
    "contexts:usage",
    "contexts:billing-client",
    "contexts:webhook",
    "apps:app-boot",
    "testing:arch-fixtures",
)
