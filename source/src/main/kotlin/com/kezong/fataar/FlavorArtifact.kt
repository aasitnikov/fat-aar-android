package com.kezong.fataar

import org.gradle.api.NamedDomainObjectContainer
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.artifacts.ResolvedDependency
import org.gradle.api.tasks.TaskProvider

/**
 * Resolves an embedded sub-project dependency that Gradle could not resolve to an artifact
 * directly (typically because the consumer and the embedded library use different flavor
 * dimensions and the dependency was declared against the "default" configuration).
 *
 * Instead of synthesizing a Gradle internal `ResolvedArtifact` (whose internal types vary
 * across Gradle versions and break a single compiled plugin), we return a lightweight
 * descriptor pointing at the sub-project's bundle task. The aar file is read from that task
 * at processing time using only stable public API.
 */
object FlavorArtifact {

    /**
     * Lightweight, version-stable description of an embedded aar produced by a sub-project's
     * bundle task.
     */
    data class FlavorAar(
        val group: String,
        val name: String,
        val version: String,
        val bundleTask: TaskProvider<Task>
    )

    fun resolveFlavorAar(
        consumerProject: Project,
        variantInfo: VariantInfo,
        unResolvedArtifact: ResolvedDependency
    ): FlavorAar? {
        val artifactProject = getArtifactProject(consumerProject, unResolvedArtifact) ?: return null
        val bundleProvider = try {
            getBundleTask(consumerProject, artifactProject, variantInfo)
        } catch (ex: Exception) {
            FatUtils.logError("[${variantInfo.name}]Can not resolve :${unResolvedArtifact.moduleName}", ex)
            null
        }
        if (bundleProvider == null) {
            FatUtils.logError("[${variantInfo.name}]Can not resolve :${unResolvedArtifact.moduleName}")
            return null
        }
        return FlavorAar(
            group = unResolvedArtifact.moduleGroup,
            name = unResolvedArtifact.moduleName,
            version = unResolvedArtifact.moduleVersion,
            bundleTask = bundleProvider
        )
    }

    private fun getArtifactProject(project: Project, unResolvedArtifact: ResolvedDependency): Project? {
        for (p in project.rootProject.allprojects) {
            if (unResolvedArtifact.moduleName == p.name && unResolvedArtifact.moduleGroup == p.group.toString()) {
                return p
            }
        }
        return null
    }

    /**
     * Resolve the artifact project's bundle task for the consuming variant.
     */
    private fun getBundleTask(
        consumerProject: Project,
        artifactProject: Project,
        variantInfo: VariantInfo
    ): TaskProvider<Task>? {
        // 1. Simple matching: same variant / build type / flavor (handles same-dimension cases).
        val candidates = mutableListOf<String>()
        candidates.add(variantInfo.name)
        variantInfo.buildTypeName?.let { candidates.add(it) }
        if (variantInfo.flavorName != null && variantInfo.buildTypeName != null) {
            candidates.add(variantInfo.flavorName + variantInfo.buildTypeName.replaceFirstChar { it.uppercase() })
        }
        variantInfo.flavorName?.let { candidates.add(it) }

        for (candidate in candidates) {
            try {
                return VersionAdapter.getBundleTaskProvider(artifactProject, candidate)
            } catch (_: Exception) {
            }
        }

        // 2. Cross-dimension matching via the consuming flavor's missingDimensionStrategy.
        return resolveByMissingDimensionStrategy(consumerProject, artifactProject, variantInfo)
    }

    /**
     * When the consumer and the embedded library use different flavor dimensions, the
     * consumer declares a `missingDimensionStrategy` mapping each foreign dimension to a
     * preferred flavor (plus fallbacks). Use that mapping to pick the embedded library's
     * bundle task for the matching build type. AGP-version-agnostic (reflection + graceful
     * fallback), so it keeps working from AGP 8.3 up to the latest.
     */
    private fun resolveByMissingDimensionStrategy(
        consumerProject: Project,
        artifactProject: Project,
        variantInfo: VariantInfo
    ): TaskProvider<Task>? {
        try {
            val buildType = variantInfo.buildTypeName ?: return null
            val consumerFlavor = variantInfo.flavorName ?: return null

            val strategies = getMissingDimensionStrategies(consumerProject, consumerFlavor)
            if (strategies.isEmpty()) return null

            val artifactFlavors = getProductFlavorDimensions(artifactProject)
            if (artifactFlavors.isEmpty()) return null

            for ((dimension, requestedFlavors) in strategies) {
                for (requested in requestedFlavors) {
                    val match = artifactFlavors.firstOrNull { it.first == requested && it.second == dimension }
                        ?: continue
                    val targetFlavor = match.first
                    val variantCandidate = targetFlavor + buildType.replaceFirstChar { it.uppercase() }
                    try {
                        return VersionAdapter.getBundleTaskProvider(artifactProject, variantCandidate)
                    } catch (_: Exception) {
                    }
                    try {
                        return VersionAdapter.getBundleTaskProvider(artifactProject, targetFlavor)
                    } catch (_: Exception) {
                    }
                }
            }
        } catch (e: Exception) {
            FatUtils.logInfo("missingDimensionStrategy resolution failed for ${artifactProject.name}: ${e.message}")
        }
        return null
    }

    /**
     * Reads a consumer flavor's missingDimensionStrategies as dimension -> [requested, fallbacks...].
     */
    private fun getMissingDimensionStrategies(project: Project, flavorName: String): Map<String, List<String>> {
        val result = linkedMapOf<String, List<String>>()
        val android = project.extensions.getByName("android")
        val productFlavors = android.javaClass.getMethod("getProductFlavors").invoke(android)
                as NamedDomainObjectContainer<*>
        val flavor = productFlavors.findByName(flavorName) ?: return result
        val map = try {
            flavor.javaClass.getMethod("getMissingDimensionStrategies").invoke(flavor) as? Map<*, *>
        } catch (_: Exception) {
            null
        } ?: return result

        for ((key, value) in map) {
            val dimension = key as? String ?: continue
            if (value == null) continue
            val requested = try {
                value.javaClass.getMethod("getRequested").invoke(value) as? String
            } catch (_: Exception) {
                null
            }
            @Suppress("UNCHECKED_CAST")
            val fallbacks = try {
                value.javaClass.getMethod("getFallbacks").invoke(value) as? List<String>
            } catch (_: Exception) {
                null
            } ?: emptyList()

            val flavors = mutableListOf<String>()
            requested?.let { flavors.add(it) }
            flavors.addAll(fallbacks)
            if (flavors.isNotEmpty()) result[dimension] = flavors
        }
        return result
    }

    /**
     * Reads a project's product flavors as a list of (flavorName -> dimension).
     */
    private fun getProductFlavorDimensions(project: Project): List<Pair<String, String>> {
        val result = mutableListOf<Pair<String, String>>()
        val android = project.extensions.getByName("android")
        val productFlavors = android.javaClass.getMethod("getProductFlavors").invoke(android)
                as NamedDomainObjectContainer<*>
        productFlavors.forEach { flavor ->
            if (flavor == null) return@forEach
            val name = flavor.javaClass.getMethod("getName").invoke(flavor) as String
            val dimension = try {
                flavor.javaClass.getMethod("getDimension").invoke(flavor) as? String
            } catch (_: Exception) {
                null
            }
            if (dimension != null) result.add(name to dimension)
        }
        return result
    }
}
