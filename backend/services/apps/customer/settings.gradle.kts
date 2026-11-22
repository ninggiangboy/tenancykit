pluginManagement {
    includeBuild("build-logic")
}

// The technical core every service builds against, shared as a separate build by its coordinates.
includeBuild("../../../libs/core")

dependencyResolutionManagement {
    repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
    repositories { mavenCentral() }
}

rootProject.name = "customer"

include(
    "libs:shared-core",
    "libs:jooq-schema",
    "contexts:platform",
    "contexts:customers",
    "contexts:orders",
    "contexts:activity",
    "contexts:profile",
    "contexts:webhook",
    "apps:app-boot",
    "testing:arch-fixtures",
)
