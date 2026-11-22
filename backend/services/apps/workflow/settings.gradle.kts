pluginManagement {
    includeBuild("build-logic")
}

// The technical core every service builds against, shared as a separate build by its coordinates.
includeBuild("../../../libs/core")

dependencyResolutionManagement {
    repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
    repositories { mavenCentral() }
}

rootProject.name = "workflow"

include(
    "libs:shared-core",
    "libs:jooq-schema",
    "contexts:platform",
    "contexts:definitions",
    "contexts:runtime",
    "contexts:triggers",
    "contexts:connections",
    "contexts:webhook",
    "contexts:tasks",
    "apps:app-boot",
    "testing:arch-fixtures",
)
