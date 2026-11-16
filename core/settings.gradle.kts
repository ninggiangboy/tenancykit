pluginManagement {
    includeBuild("build-logic")
}

dependencyResolutionManagement {
    repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
    repositories { mavenCentral() }
}

rootProject.name = "core"

include("core-kernel", "core-spring", "core-messaging", "core-replica", "core-platform-sync", "core-suite", "core-feign", "core-webhook", "core-visitor", "core-arch")
