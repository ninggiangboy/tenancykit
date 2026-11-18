import org.gradle.api.artifacts.ExternalModuleDependency
import org.gradle.api.artifacts.ProjectDependency

/*
 * Services meet through the broker, the replica and signed tokens, never through each other's code. This check
 * fails the build when a mailer project depends on another service, or on a core module that only a service with
 * a replica or an HTTP edge runs. Mailer needs `core-messaging`, so it is deliberately absent from the
 * service-only list: a consumer of commands is entitled to the broker's infrastructure.
 *
 * Two axes are needed, not one. A `ProjectDependency` can only exist inside this build, so matching its path
 * segments catches an accident here; a sibling service reaches this build as an external coordinate, whose only
 * discriminator is the dependency's group. A gate with the project axis alone is decorative: it can never fire on
 * `dev.ngb.billing`, which is the case that matters.
 */
val foreignServices = setOf("billing", "platform", "tenant")
val foreignGroups = setOf("dev.ngb.billing", "dev.ngb.platform", "dev.ngb.tenant")
val serviceOnlyCoreModules = setOf("core-replica", "core-platform-sync", "core-suite")

val checkNoForeignServiceDependency = tasks.register("checkNoForeignServiceDependency") {
    group = "verification"
    description = "Fails when a mailer project depends on another service's code or a service-only core module."
    doLast {
        val offenders = allprojects.flatMap { project ->
            project.configurations.flatMap { configuration ->
                configuration.dependencies.mapNotNull { dependency ->
                    when (dependency) {
                        is ProjectDependency ->
                            dependency.takeIf { it.path.split(":").any(foreignServices::contains) }
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
