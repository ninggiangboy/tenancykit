import org.gradle.api.artifacts.ExternalModuleDependency
import org.gradle.api.artifacts.ProjectDependency

/*
 * Services meet through the broker, the replica and signed tokens, never through each other's code. This check fails the
 * build when a loyalty project depends on another service.
 *
 * Two axes are needed, not one, and neither names a service:
 *
 *  - A sibling service reaches this build as an external coordinate whose group is `dev.ngb.<service>`, while the shared
 *    core every app builds against uses the group `dev.ngb` itself. So any `dev.ngb.` group is foreign and `dev.ngb` is
 *    not, which bans every sibling service — including the one delivered beside this app — without a name list that would
 *    go stale as the suite grows.
 *  - A `ProjectDependency` can only exist inside this build, so its path must start with one of this build's own trees.
 *
 * `serviceOnlyCoreModules` is empty: loyalty is a replica consumer with an HTTP edge and a webhook sender, so it needs
 * `core-replica`, `core-platform-sync`, `core-suite` and `core-webhook`. A shorter list would fail on the first
 * legitimate dependency, so the gate rests on the foreign groups alone.
 */
val foreignGroupPrefix = "dev.ngb."
val ownTrees = setOf("libs", "contexts", "apps", "testing")
val serviceOnlyCoreModules = emptySet<String>()

val checkNoForeignServiceDependency = tasks.register("checkNoForeignServiceDependency") {
    group = "verification"
    description = "Fails when a loyalty project depends on another service's code."
    doLast {
        val offenders = allprojects.flatMap { project ->
            project.configurations.flatMap { configuration ->
                configuration.dependencies.mapNotNull { dependency ->
                    when (dependency) {
                        is ProjectDependency ->
                            dependency.takeIf {
                                it.path.split(":").filter(String::isNotEmpty).firstOrNull() !in ownTrees
                            }?.let { "${project.path}:${configuration.name} -> ${it.path}" }
                        is ExternalModuleDependency ->
                            dependency
                                .takeIf {
                                    it.group?.startsWith(foreignGroupPrefix) == true ||
                                        (it.group == "dev.ngb" && serviceOnlyCoreModules.contains(it.name))
                                }
                                ?.let { "${project.path}:${configuration.name} -> ${it.group}:${it.name}" }
                        else -> null
                    }
                }
            }
        }
        if (offenders.isNotEmpty()) {
            throw GradleException(
                "A service must not depend on another service's code:\n" + offenders.joinToString("\n"),
            )
        }
    }
}

// `./gradlew check` runs the suite's isolation gate with every other verification task.
subprojects {
    plugins.withId("java") {
        tasks.named("check") { dependsOn(checkNoForeignServiceDependency) }
    }
}
