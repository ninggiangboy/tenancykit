import org.gradle.api.artifacts.ExternalModuleDependency
import org.gradle.api.artifacts.ProjectDependency

/*
 * Services meet through the broker, the replica and signed tokens, never through each other's code. This check
 * fails the build when a customer project depends on another service.
 *
 * `serviceOnlyCoreModules` is empty: customer is a replica consumer with an HTTP edge and an in-process webhook sender,
 * so it legitimately needs `core-replica`, `core-platform-sync`, `core-suite` and `core-webhook`. The gate rests on
 * the foreign groups alone.
 *
 * Two axes are needed, not one. A `ProjectDependency` can only exist inside this build; its path is matched by the
 * root segment, because this build legitimately owns a `contexts:platform` module and a segment match would flag it.
 * A sibling service reaches this build as an external coordinate, whose only discriminator is the dependency's group.
 * loyalty is in both lists: customer and loyalty are separate deployables that share only `core/` and Platform's events.
 */
val foreignServices =
    setOf("billing", "mailer", "platform", "tenant", "mailroom", "survey", "attribution", "notification", "loyalty")
val foreignGroups = setOf(
    "dev.ngb.billing", "dev.ngb.mailer", "dev.ngb.platform", "dev.ngb.tenant", "dev.ngb.mailroom",
    "dev.ngb.survey", "dev.ngb.attribution", "dev.ngb.notification", "dev.ngb.loyalty",
)
val serviceOnlyCoreModules = emptySet<String>()

val checkNoForeignServiceDependency = tasks.register("checkNoForeignServiceDependency") {
    group = "verification"
    description = "Fails when a customer project depends on another service's code."
    doLast {
        val offenders = allprojects.flatMap { project ->
            project.configurations.flatMap { configuration ->
                configuration.dependencies.mapNotNull { dependency ->
                    when (dependency) {
                        is ProjectDependency ->
                            dependency.takeIf { it.path.split(":").filter(String::isNotEmpty).firstOrNull() in foreignServices }
                                ?.let { "${project.path}:${configuration.name} -> ${it.path}" }
                        is ExternalModuleDependency ->
                            dependency
                                .takeIf {
                                    it.group in foreignGroups ||
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
