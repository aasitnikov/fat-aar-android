package com.kezong.fataar

import com.android.build.api.variant.AndroidComponentsExtension
import com.android.build.api.variant.Variant
import com.android.build.gradle.api.LibraryVariant
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.ProjectConfigurationException
import org.gradle.api.artifacts.Configuration
import org.gradle.api.artifacts.ResolvedArtifact
import org.gradle.api.artifacts.ResolvedDependency
import org.gradle.api.internal.file.FileResolver
import org.gradle.api.internal.tasks.TaskDependencyFactory
import org.gradle.api.provider.MapProperty
import org.gradle.api.tasks.TaskProvider
import org.gradle.internal.model.CalculatedValueContainerFactory

import javax.inject.Inject

/**
 * plugin entry
 */
class FatAarPlugin implements Plugin<Project> {

    public static final String ARTIFACT_TYPE_AAR = 'aar'

    public static final String ARTIFACT_TYPE_JAR = 'jar'

    private static final String CONFIG_NAME = "embed"

    public static final String CONFIG_SUFFIX = 'Embed'

    private Project project

    private final Collection<Configuration> embedConfigurations = new ArrayList<>()

    private MapProperty<String, List<AndroidArchiveLibrary>> variantPackagesProperty

    private final List<VariantInfo> variantInfos = new ArrayList<>()

    private CalculatedValueContainerFactory calculatedValueContainerFactory

    private TaskDependencyFactory taskDependencyFactory

    private FileResolver fileResolver

    @Inject
    FatAarPlugin(CalculatedValueContainerFactory calculatedValueContainerFactory,
                 TaskDependencyFactory taskDependencyFactory,
                 FileResolver fileResolver) {
        this.calculatedValueContainerFactory = calculatedValueContainerFactory
        this.taskDependencyFactory = taskDependencyFactory
        this.fileResolver = fileResolver
    }

    @Override
    void apply(Project project) {
        this.project = project
        checkAndroidPlugin()
        FatUtils.attach(project)
        project.extensions.create(FatAarExtension.NAME, FatAarExtension)
        createConfigurations()
        registerTransform()
        project.afterEvaluate {
            doAfterEvaluate()
        }
        registerAndroidComponents()
    }

    private registerTransform() {
        variantPackagesProperty = project.objects.mapProperty(String.class, List.class)
        FatAarPluginHelper.registerAsmTransformation(project, variantPackagesProperty)
    }

    private void registerAndroidComponents() {
        AndroidComponentsExtension<?, ?, Variant> components =
                project.extensions.getByType(AndroidComponentsExtension.class)
        components.onVariants(components.selector().all()) { variant ->
            VariantInfo variantInfo = VariantInfo.fromNew(variant)
            registerResMergeTask(variant)
            registerAssetsMergeTask(variant)
            if (!variantPackagesProperty.getOrElse([:]).containsKey(variantInfo.name)) {
                variantPackagesProperty.put(variantInfo.name, project.objects.listProperty(AndroidArchiveLibrary.class))
            }
            variantInfos.add(variantInfo)
        }
    }

    /**
     * Registers the embedded-res merge task and wires its output into the variant's Android
     * resources. This MUST happen inside the {@code onVariants} callback: the Sources API
     * ({@link com.android.build.api.variant.SourceDirectories#addGeneratedSourceDirectory}) is only
     * honored while the variant is being configured; calling it later (e.g. in {@code afterEvaluate})
     * is silently ignored, leaving the fat-aar without resources.
     *
     * The task's inputs (the embedded res folders) are populated later, in
     * {@link VariantProcessor#processResources()}, once the embedded artifacts are resolved.
     */
    private void registerResMergeTask(Variant variant) {
        if (FatUtils.compareVersion(VersionAdapter.AGPVersion, "9.0.0") < 0) {
            // On AGP < 9 res is merged via the legacy path (LibraryVariant.registerGeneratedResFolders
            // in VariantProcessor.processResources); this Sources-API task is only for AGP 9+.
            return
        }
        def resSources = variant.sources.res
        if (resSources == null) {
            // Android resources are disabled for this variant; nothing to merge.
            return
        }
        TaskProvider<FatAarResMergeTask> resMergeTask = project.tasks.register(
                FatAarResMergeTask.nameFor(variant.name), FatAarResMergeTask
        )
        // The wired property MUST be the task's own @OutputDirectory: AGP overrides it (via
        // convention) with a build-managed location and makes the res pipeline depend on the task.
        resSources.addGeneratedSourceDirectory(resMergeTask) { it.outputDirectory }
    }

    /**
     * Registers the embedded-assets merge task and wires its output into the variant's Android
     * assets, mirroring {@link #registerResMergeTask(Variant)} for {@code assets} instead of
     * {@code res}. Without this, embedded AARs' {@code assets/} directories are silently dropped
     * from the fat AAR under AGP 9+: {@link VariantProcessor#processAssets()}'s legacy fallback
     * mutates {@code android.sourceSets[variant].assets.srcDir(...)} inside the merge task's
     * {@code doFirst}, but AGP 9's assets-merging task no longer reads that source set at
     * execution time — its inputs are fixed by the Sources API during variant configuration.
     *
     * The task's inputs (the embedded assets folders) are populated later, in
     * {@link VariantProcessor#processAssets()}, once the embedded artifacts are resolved.
     */
    private void registerAssetsMergeTask(Variant variant) {
        if (FatUtils.compareVersion(VersionAdapter.AGPVersion, "9.0.0") < 0) {
            // On AGP < 9 assets are merged via the legacy path (SourceDirectorySet.srcDir(...) in
            // VariantProcessor.processAssets); this Sources-API task is only for AGP 9+.
            return
        }
        def assetsSources = variant.sources.assets
        if (assetsSources == null) {
            // Assets are disabled for this variant; nothing to merge.
            return
        }
        TaskProvider<FatAarAssetsMergeTask> assetsMergeTask = project.tasks.register(
                FatAarAssetsMergeTask.nameFor(variant.name), FatAarAssetsMergeTask
        )
        // The wired property MUST be the task's own @OutputDirectory: AGP overrides it (via
        // convention) with a build-managed location and makes the assets pipeline depend on it.
        assetsSources.addGeneratedSourceDirectory(assetsMergeTask) { it.outputDirectory }
    }

    private void doAfterEvaluate() {
        embedConfigurations.each {
            if (project.fataar.transitive) {
                it.transitive = true
            }
        }

        if (FatUtils.compareVersion(VersionAdapter.AGPVersion, "9.0.0") >= 0) {
            variantInfos.each { variantInfo ->
                processVariantWithInfo(variantInfo)
            }
            return
        }

        project.android.libraryVariants.all { variant ->
            Collection<ResolvedArtifact> artifacts = new ArrayList()
            Collection<ResolvedDependency> firstLevelDependencies = new ArrayList<>()
            embedConfigurations.each { configuration ->
                if (configuration.name == CONFIG_NAME
                        || configuration.name == variant.getBuildType().name + CONFIG_SUFFIX
                        || configuration.name == variant.getFlavorName() + CONFIG_SUFFIX
                        || configuration.name == variant.name + CONFIG_SUFFIX) {
                    Collection<ResolvedArtifact> resolvedArtifacts = resolveArtifacts(configuration)
                    artifacts.addAll(resolvedArtifacts)
                    artifacts.addAll(dealUnResolveArtifacts(configuration, variant as LibraryVariant, resolvedArtifacts))
                    firstLevelDependencies.addAll(configuration.resolvedConfiguration.firstLevelModuleDependencies)
                }
            }

            if (!artifacts.isEmpty()) {
                def processor = new VariantProcessor(project, variant, variantPackagesProperty)
                processor.processVariant(artifacts, firstLevelDependencies)
            }
        }
    }

    private void processVariantWithInfo(VariantInfo variantInfo) {
        embedConfigurations.each {
            if (project.fataar.transitive) {
                it.transitive = true
            }
        }

        Collection<ResolvedArtifact> artifacts = new ArrayList()
        Collection<ResolvedDependency> firstLevelDependencies = new ArrayList<>()
        embedConfigurations.each { configuration ->
            if (configuration.name == CONFIG_NAME
                    || (variantInfo.buildTypeName != null && configuration.name == variantInfo.buildTypeName + CONFIG_SUFFIX)
                    || (variantInfo.flavorName != null && configuration.name == variantInfo.flavorName + CONFIG_SUFFIX)
                    || configuration.name == variantInfo.name + CONFIG_SUFFIX) {
                Collection<ResolvedArtifact> resolvedArtifacts = resolveArtifacts(configuration)
                artifacts.addAll(resolvedArtifacts)
                artifacts.addAll(dealUnResolveArtifacts(configuration, variantInfo, resolvedArtifacts))
                firstLevelDependencies.addAll(configuration.resolvedConfiguration.firstLevelModuleDependencies)
            }
        }

        if (!artifacts.isEmpty()) {
            def processor = new VariantProcessor(project, variantInfo, variantPackagesProperty)
            processor.processVariant(artifacts, firstLevelDependencies)
        }
    }

    private void createConfigurations() {
        Configuration embedConf = project.configurations.create(CONFIG_NAME)
        createConfiguration(embedConf)
        FatUtils.logInfo("Creating configuration embed")

        project.android.buildTypes.all { buildType ->
            String configName = buildType.name + CONFIG_SUFFIX
            Configuration configuration = project.configurations.create(configName)
            createConfiguration(configuration)
            FatUtils.logInfo("Creating configuration " + configName)
        }

        project.android.productFlavors.all { flavor ->
            String configName = flavor.name + CONFIG_SUFFIX
            Configuration configuration = project.configurations.create(configName)
            createConfiguration(configuration)
            FatUtils.logInfo("Creating configuration " + configName)
            project.android.buildTypes.all { buildType ->
                String variantName = flavor.name + buildType.name.capitalize()
                String variantConfigName = variantName + CONFIG_SUFFIX
                Configuration variantConfiguration = project.configurations.create(variantConfigName)
                createConfiguration(variantConfiguration)
                FatUtils.logInfo("Creating configuration " + variantConfigName)
            }
        }
    }

    private void checkAndroidPlugin() {
        if (!project.plugins.hasPlugin('com.android.library')) {
            throw new ProjectConfigurationException('fat-aar-plugin must be applied in project that' +
                    ' has android library plugin!', null)
        }
    }

    private void createConfiguration(Configuration embedConf) {
        embedConf.visible = false
        embedConf.transitive = false
        project.gradle.addListener(new EmbedResolutionListener(project, embedConf))
        embedConfigurations.add(embedConf)
    }

    private static Collection<ResolvedArtifact> resolveArtifacts(Configuration configuration) {
        def set = new ArrayList()
        if (configuration != null) {
            configuration.resolvedConfiguration.resolvedArtifacts.each { artifact ->
                if (ARTIFACT_TYPE_AAR == artifact.type || ARTIFACT_TYPE_JAR == artifact.type) {
                    //
                } else {
                    throw new ProjectConfigurationException('Only support embed aar and jar dependencies!', null)
                }
                set.add(artifact)
            }
        }
        return set
    }

    private Collection<ResolvedArtifact> dealUnResolveArtifacts(Configuration configuration,
                                                                LibraryVariant variant,
                                                                Collection<ResolvedArtifact> artifacts) {
        def artifactList = new ArrayList()
        configuration.resolvedConfiguration.firstLevelModuleDependencies.each { dependency ->
            def match = artifacts.any { artifact ->
                dependency.moduleName == artifact.moduleVersion.id.name
            }

            if (!match) {
                def flavorArtifact = FlavorArtifact.createFlavorArtifact(
                        project, variant, dependency, calculatedValueContainerFactory, fileResolver, taskDependencyFactory
                )
                if (flavorArtifact != null) {
                    artifactList.add(flavorArtifact)
                }
            }
        }
        return artifactList
    }

    private Collection<ResolvedArtifact> dealUnResolveArtifacts(Configuration configuration,
                                                                VariantInfo variantInfo,
                                                                Collection<ResolvedArtifact> artifacts) {
        def artifactList = new ArrayList()
        configuration.resolvedConfiguration.firstLevelModuleDependencies.each { dependency ->
            def match = artifacts.any { artifact ->
                dependency.moduleName == artifact.moduleVersion.id.name
            }

            if (!match) {
                def flavorArtifact = FlavorArtifact.createFlavorArtifact(
                        project, variantInfo, dependency, calculatedValueContainerFactory, fileResolver, taskDependencyFactory
                )
                if (flavorArtifact != null) {
                    artifactList.add(flavorArtifact)
                }
            }
        }
        return artifactList
    }
}
