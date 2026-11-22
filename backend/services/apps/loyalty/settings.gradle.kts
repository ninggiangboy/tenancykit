pluginManagement {
    includeBuild("build-logic")
}

// The technical core every service builds against, shared as a separate build by its coordinates.
includeBuild("../../../libs/core")

dependencyResolutionManagement {
    repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
    repositories { mavenCentral() }
}

rootProject.name = "loyalty"

include(
    "libs:shared-core",
    "libs:jooq-schema",
    "contexts:platform",
    "contexts:program",
    "contexts:points",
    "contexts:tiers",
    "contexts:rewards",
    "contexts:webhook",
    "apps:app-boot",
    "testing:arch-fixtures",
)
