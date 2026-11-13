import org.gradle.api.artifacts.ExternalModuleDependency
import org.gradle.api.artifacts.ProjectDependency

/*
 * The two systems share contracts and the technical core, never another service's code. This check fails the
 * build when a tenant project declares a Gradle dependency on Billing or Platform, or on a core module that
 * only a service runs (the replica and the messaging infrastructure). The technical core is included as a
 * separate build and found by its coordinates, so `dev.ngb:core-kernel` and `dev.ngb:core-spring` pass.
 */
val foreignServices = setOf("billing", "platform")
val serviceOnlyCoreModules = setOf("core-messaging", "core-replica")

tasks.register("checkNoBillingDependency") {
    group = "verification"
    description = "Fails when a tenant project depends on another service's code or a service-only core module."
    doLast {
        val offenders = allprojects.flatMap { project ->
            project.configurations.flatMap { configuration ->
                configuration.dependencies.mapNotNull { dependency ->
                    when (dependency) {
                        is ProjectDependency ->
                            dependency.takeIf { it.path.split(":").any(foreignServices::contains) }
                                ?.let { "${project.path}:${configuration.name} -> ${it.path}" }
                        is ExternalModuleDependency ->
                            dependency.takeIf { it.group == "dev.ngb" && serviceOnlyCoreModules.contains(it.name) }
                                ?.let { "${project.path}:${configuration.name} -> ${it.group}:${it.name}" }
                        else -> null
                    }
                }
            }
        }
        if (offenders.isNotEmpty()) {
            throw GradleException(
                "The sample tenant must not depend on another service or a service-only core module:\n" +
                    offenders.joinToString("\n"),
            )
        }
    }
}
