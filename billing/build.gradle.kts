import org.gradle.api.artifacts.ExternalModuleDependency
import org.gradle.api.artifacts.ProjectDependency

/*
 * Services meet through the broker, the replica and signed tokens, never through each other's code. This check
 * fails the build when a Billing project declares a Gradle dependency on Platform or the sample tenant. Billing
 * shares `core-messaging` and `core-replica` with Platform by design; what a service may not do is reach another
 * service.
 */
val foreignServices = listOf("platform", "tenant")

val checkNoForeignServiceDependency = tasks.register("checkNoForeignServiceDependency") {
    group = "verification"
    description = "Fails when a Billing project depends on another service's code."
    doLast {
        val offenders = allprojects.flatMap { project ->
            project.configurations.flatMap { configuration ->
                configuration.dependencies.mapNotNull { dependency ->
                    when (dependency) {
                        is ProjectDependency ->
                            dependency.takeIf { it.path.split(":").any(foreignServices::contains) }
                                ?.let { "${project.path}:${configuration.name} -> ${it.path}" }
                        is ExternalModuleDependency ->
                            dependency.takeIf { it.group in setOf("dev.ngb.platform", "dev.ngb.tenant") }
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
