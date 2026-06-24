package com.kezong.fataar

import com.android.build.api.variant.AndroidComponentsExtension
import com.android.build.api.variant.Variant
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.ProjectConfigurationException
import org.gradle.api.artifacts.Configuration
import org.gradle.api.artifacts.ResolvedArtifact
import org.gradle.api.artifacts.ResolvedDependency
import org.gradle.api.provider.MapProperty

/**
 * Plugin entry point.
 *
 * Note: this plugin intentionally avoids Gradle internal APIs and constructor-injected
 * internal services so a single compiled artifact runs across AGP 8.3 (Gradle 8.4) to the latest.
 */
class FatAarPlugin : Plugin<Project> {

    companion object {
        const val ARTIFACT_TYPE_AAR = "aar"
        const val ARTIFACT_TYPE_JAR = "jar"
        private const val CONFIG_NAME = "embed"
        const val CONFIG_SUFFIX = "Embed"
    }

    private lateinit var project: Project
    private val embedConfigurations = mutableListOf<Configuration>()
    private lateinit var variantPackagesProperty: MapProperty<String, List<AndroidArchiveLibrary>>
    private val variantInfos = mutableListOf<VariantInfo>()

    override fun apply(project: Project) {
        this.project = project
        checkAndroidPlugin()
        FatUtils.attach(project)
        project.extensions.create(FatAarExtension.NAME, FatAarExtension::class.java)
        createConfigurations()
        registerTransform()
        project.afterEvaluate { doAfterEvaluate() }
        registerAndroidComponents()
    }

    private fun registerTransform() {
        @Suppress("UNCHECKED_CAST")
        variantPackagesProperty = project.objects.mapProperty(String::class.java, List::class.java) as MapProperty<String, List<AndroidArchiveLibrary>>
        FatAarPluginHelper.registerAsmTransformation(project, variantPackagesProperty)
    }

    private fun registerAndroidComponents() {
        val components = project.extensions.getByType(AndroidComponentsExtension::class.java)
        components.onVariants(components.selector().all()) { variant: Variant ->
            val variantInfo = VariantInfo.fromNew(variant, project)
            if (!variantPackagesProperty.getOrElse(emptyMap()).containsKey(variantInfo.name)) {
                variantPackagesProperty.put(
                    variantInfo.name,
                    project.objects.listProperty(AndroidArchiveLibrary::class.java)
                )
            }
            variantInfos.add(variantInfo)
        }
    }

    private fun doAfterEvaluate() {
        val fataarExt = project.extensions.getByType(FatAarExtension::class.java)
        embedConfigurations.forEach {
            if (fataarExt.transitive) {
                it.isTransitive = true
            }
        }

        // Auto-add embedded project dependencies' res dirs to source sets for R class generation
        addEmbeddedProjectResources()

        variantInfos.forEach { variantInfo ->
            processVariantWithInfo(variantInfo)
        }
    }

    /**
     * Automatically adds resource directories from embedded project dependencies
     * to the main source set so R class generation includes all embedded resources.
     * This eliminates the need for manually configuring sourceSets in build.gradle.
     */
    private fun addEmbeddedProjectResources() {
        val android = project.extensions.getByName("android")
        val sourceSets = android.javaClass.getMethod("getSourceSets").invoke(android) as Iterable<*>
        var mainSourceSet: Any? = null
        for (sourceSet in sourceSets) {
            val ssName = sourceSet!!.javaClass.getMethod("getName").invoke(sourceSet) as String
            if (ssName == "main") {
                mainSourceSet = sourceSet
                break
            }
        }
        if (mainSourceSet == null) return

        val res = mainSourceSet.javaClass.getMethod("getRes").invoke(mainSourceSet)

        embedConfigurations.forEach { configuration ->
            configuration.dependencies.forEach { dependency ->
                if (dependency is org.gradle.api.artifacts.ProjectDependency) {
                    val depProject = resolveDependencyProject(dependency) ?: return@forEach
                    val resDir = depProject.file("src/main/res")
                    if (resDir.exists()) {
                        FatUtils.logInfo("Auto-adding res dir from embedded project '${depProject.path}': $resDir")
                        res.javaClass.getMethod("srcDir", Any::class.java).invoke(res, resDir)
                    }
                }
            }
        }
    }

    /**
     * Resolve the [Project] behind a [ProjectDependency] across Gradle versions:
     *  - Gradle 8.11+/9.x expose `getPath()` (and removed `getDependencyProject()`).
     *  - Older Gradle (8.4-8.10) expose `getDependencyProject()`.
     * Done reflectively so a single compiled plugin works on Gradle 8.4 through 9.x.
     */
    private fun resolveDependencyProject(dependency: org.gradle.api.artifacts.ProjectDependency): Project? {
        try {
            val path = dependency.javaClass.getMethod("getPath").invoke(dependency) as String
            return project.project(path)
        } catch (_: Throwable) {
        }
        try {
            return dependency.javaClass.getMethod("getDependencyProject").invoke(dependency) as Project
        } catch (_: Throwable) {
        }
        return null
    }

    private fun processVariantWithInfo(variantInfo: VariantInfo) {
        val artifacts = mutableListOf<ResolvedArtifact>()
        val flavorAars = mutableListOf<FlavorArtifact.FlavorAar>()
        val firstLevelDependencies = mutableListOf<ResolvedDependency>()

        embedConfigurations.forEach { configuration ->
            if (configuration.name == CONFIG_NAME
                || (variantInfo.buildTypeName != null && configuration.name == variantInfo.buildTypeName + CONFIG_SUFFIX)
                || (variantInfo.flavorName != null && configuration.name == variantInfo.flavorName + CONFIG_SUFFIX)
                || configuration.name == variantInfo.name + CONFIG_SUFFIX
            ) {
                val resolvedArtifacts = resolveArtifacts(configuration)
                artifacts.addAll(resolvedArtifacts)
                flavorAars.addAll(dealUnResolveArtifacts(configuration, variantInfo, resolvedArtifacts))
                firstLevelDependencies.addAll(configuration.resolvedConfiguration.firstLevelModuleDependencies)
            }
        }

        if (artifacts.isNotEmpty() || flavorAars.isNotEmpty()) {
            val processor = VariantProcessor(project, variantInfo, variantPackagesProperty)
            processor.processVariant(artifacts, flavorAars, firstLevelDependencies)
        }
    }

    private fun createConfigurations() {
        val embedConf = project.configurations.create(CONFIG_NAME)
        createConfiguration(embedConf)
        FatUtils.logInfo("Creating configuration embed")

        val android = project.extensions.getByName("android")
        val buildTypes = android.javaClass.getMethod("getBuildTypes").invoke(android) as org.gradle.api.NamedDomainObjectContainer<*>
        buildTypes.configureEach {
            val btName = this.javaClass.getMethod("getName").invoke(this) as String
            val configName = btName + CONFIG_SUFFIX
            val configuration = project.configurations.create(configName)
            createConfiguration(configuration)
            FatUtils.logInfo("Creating configuration $configName")
        }

        val productFlavors = android.javaClass.getMethod("getProductFlavors").invoke(android) as org.gradle.api.NamedDomainObjectContainer<*>
        productFlavors.configureEach {
            val flavorName = this.javaClass.getMethod("getName").invoke(this) as String
            val configName = flavorName + CONFIG_SUFFIX
            val configuration = project.configurations.create(configName)
            createConfiguration(configuration)
            FatUtils.logInfo("Creating configuration $configName")

            buildTypes.configureEach {
                val btName = this.javaClass.getMethod("getName").invoke(this) as String
                val variantName = flavorName + btName.replaceFirstChar { it.uppercase() }
                val variantConfigName = variantName + CONFIG_SUFFIX
                val variantConfiguration = project.configurations.create(variantConfigName)
                createConfiguration(variantConfiguration)
                FatUtils.logInfo("Creating configuration $variantConfigName")
            }
        }
    }

    private fun checkAndroidPlugin() {
        if (!project.plugins.hasPlugin("com.android.library")) {
            throw ProjectConfigurationException(
                "fat-aar-plugin must be applied in project that has android library plugin!",
                listOf()
            )
        }
    }

    private fun createConfiguration(embedConf: Configuration) {
        embedConf.isVisible = false
        embedConf.isTransitive = false
        project.gradle.addListener(EmbedResolutionListener(project, embedConf))
        embedConfigurations.add(embedConf)
    }

    private fun resolveArtifacts(configuration: Configuration): Collection<ResolvedArtifact> {
        val set = mutableListOf<ResolvedArtifact>()
        configuration.resolvedConfiguration.resolvedArtifacts.forEach { artifact ->
            if (ARTIFACT_TYPE_AAR != artifact.type && ARTIFACT_TYPE_JAR != artifact.type) {
                throw ProjectConfigurationException("Only support embed aar and jar dependencies!", listOf())
            }
            set.add(artifact)
        }
        return set
    }

    private fun dealUnResolveArtifacts(
        configuration: Configuration,
        variantInfo: VariantInfo,
        artifacts: Collection<ResolvedArtifact>
    ): Collection<FlavorArtifact.FlavorAar> {
        val flavorAars = mutableListOf<FlavorArtifact.FlavorAar>()
        configuration.resolvedConfiguration.firstLevelModuleDependencies.forEach { dependency ->
            val match = artifacts.any { artifact ->
                dependency.moduleName == artifact.moduleVersion.id.name
            }
            if (!match) {
                val flavorAar = FlavorArtifact.resolveFlavorAar(project, variantInfo, dependency)
                if (flavorAar != null) {
                    flavorAars.add(flavorAar)
                }
            }
        }
        return flavorAars
    }
}
