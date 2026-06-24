package com.kezong.fataar

import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration
import org.gradle.api.artifacts.DependencyResolutionListener
import org.gradle.api.artifacts.ResolvableDependencies
import org.gradle.api.internal.artifacts.dependencies.DefaultProjectDependency

class EmbedResolutionListener(
    private val project: Project,
    private val configuration: Configuration
) : DependencyResolutionListener {

    private val compileOnlyConfigName: String

    init {
        val prefix = getConfigNamePrefix(configuration.name)
        compileOnlyConfigName = if (prefix != null) "${prefix}CompileOnly" else "compileOnly"
    }

    private fun getConfigNamePrefix(configurationName: String): String? {
        return if (configurationName.endsWith(FatAarPlugin.CONFIG_SUFFIX)) {
            configurationName.substring(0, configuration.name.length - FatAarPlugin.CONFIG_SUFFIX.length)
        } else {
            null
        }
    }

    override fun beforeResolve(resolvableDependencies: ResolvableDependencies) {
        configuration.dependencies.forEach { dependency ->
            if (dependency is DefaultProjectDependency) {
                if (dependency.targetConfiguration == null) {
                    dependency.targetConfiguration = "default"
                }
                // support that the module can be indexed in Android Studio 4.0.0
                val dependencyClone = dependency.copy()
                dependencyClone.targetConfiguration = null
                // The purpose is to support the code hints
                project.dependencies.add(compileOnlyConfigName, dependencyClone)
            } else {
                // The purpose is to support the code hints
                project.dependencies.add(compileOnlyConfigName, dependency)
            }
        }
        project.gradle.removeListener(this)
    }

    override fun afterResolve(resolvableDependencies: ResolvableDependencies) {
        // no-op
    }
}
