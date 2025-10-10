package com.kezong.fataar

import com.android.build.gradle.api.LibraryVariant
import com.android.build.gradle.tasks.ManifestProcessorTask
import com.android.manifmerger.ManifestMerger2
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.artifacts.ResolvedArtifact
import org.gradle.api.artifacts.ResolvedDependency
import org.gradle.api.file.DuplicatesStrategy
import org.gradle.api.internal.artifacts.ResolvableDependency
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.tasks.Copy
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskProvider

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Core
 * Processor for variant
 */
final class VariantProcessor {

    private final Project mProject

    private final LibraryVariant mVariant

    private final Collection<AndroidArchiveLibrary> mAndroidArchiveLibraries = new ArrayList<>()

    private final ListProperty<AndroidArchiveLibrary> mAndroidArchiveLibrariesProperty

    private final Collection<File> mJarFiles = new ArrayList<>()

    private final Collection<Task> mExplodeTasks = new ArrayList<>()

    private final VersionAdapter mVersionAdapter

    private final DirectoryManager mDirectoryManager

    VariantProcessor(Project project,
                     LibraryVariant variant,
                     MapProperty<String, List<AndroidArchiveLibrary>> variantPackagesProperty) {
        mProject = project
        mVariant = variant
        mVersionAdapter = new VersionAdapter(project, variant)
        mDirectoryManager = new DirectoryManager(project, variant)
        mAndroidArchiveLibrariesProperty = mProject.objects.listProperty(AndroidArchiveLibrary.class)
        variantPackagesProperty.put(mVariant.getName(), mAndroidArchiveLibrariesProperty)
    }

    void addAndroidArchiveLibrary(AndroidArchiveLibrary library) {
        mAndroidArchiveLibraries.add(library)
        mAndroidArchiveLibrariesProperty.add(library)
    }

    void addJarFile(File jar) {
        mJarFiles.add(jar)
    }

    void processVariant(Collection<ResolvedArtifact> artifacts,
                        Collection<ResolvableDependency> dependencies) {
        String taskPath = 'pre' + mVariant.name.capitalize() + 'Build'
        TaskProvider prepareTask = mProject.tasks.named(taskPath)
        if (prepareTask == null) {
            throw new RuntimeException("Can not find task ${taskPath}!")
        }
        TaskProvider bundleTask = VersionAdapter.getBundleTaskProvider(mProject, mVariant.name)
        preEmbed(artifacts, dependencies, prepareTask)
        processArtifacts(artifacts, prepareTask, bundleTask)
        processClassesAndJars(bundleTask)
        if (mAndroidArchiveLibraries.isEmpty()) {
            return
        }
        processManifest()
        processResources()
        processAssets()
        processJniLibs()
        processConsumerProguard()
        processGenerateProguard()
        processDataBinding(bundleTask)
        processDeepLinkTasks()
    }

    private static void printEmbedArtifacts(Collection<ResolvedArtifact> artifacts,
                                            Collection<ResolvedDependency> dependencies) {
        Collection<String> moduleNames = artifacts.stream().map { it.moduleVersion.id.name }.collect()
        dependencies.each { dependency ->
            if (!moduleNames.contains(dependency.moduleName)) {
                return
            }

            ResolvedArtifact self = dependency.allModuleArtifacts.find { module ->
                module.moduleVersion.id.name == dependency.moduleName
            }

            if (self == null) {
                return
            }

            FatUtils.logAnytime("[embed detected][$self.type]${self.moduleVersion.id}")
            moduleNames.remove(self.moduleVersion.id.name)

            dependency.allModuleArtifacts.each { artifact ->
                if (!moduleNames.contains(artifact.moduleVersion.id.name)) {
                    return
                }
                if (artifact != self) {
                    FatUtils.logAnytime("    - [embed detected][transitive][$artifact.type]${artifact.moduleVersion.id}")
                    moduleNames.remove(artifact.moduleVersion.id.name)
                }
            }
        }

        moduleNames.each { name ->
            ResolvedArtifact artifact = artifacts.find { it.moduleVersion.id.name == name }
            if (artifact != null) {
                FatUtils.logAnytime("[embed detected][$artifact.type]${artifact.moduleVersion.id}")
            }
        }
    }

    private void preEmbed(Collection<ResolvedArtifact> artifacts,
                          Collection<ResolvedDependency> dependencies,
                          TaskProvider prepareTask) {
        TaskProvider embedTask = mProject.tasks.register("pre${mVariant.name.capitalize()}Embed") {
            doFirst {
                printEmbedArtifacts(artifacts, dependencies)
            }
        }

        prepareTask.configure {
            dependsOn embedTask
        }
    }

    private void processDeepLinkTasks() {
        String taskName = "extractDeepLinksForAar${mVariant.name.capitalize()}"
        TaskProvider extractDeepLinksForAar = mProject.tasks.named(taskName)
        if (extractDeepLinksForAar == null) {
            throw new RuntimeException("Can not find task ${taskName}!")
        }

        extractDeepLinksForAar.configure {
            dependsOn(mExplodeTasks)
        }

        TaskProvider extractDeepLinks = mProject.tasks.named("extractDeepLinks${mVariant.name.capitalize()}")
        if (extractDeepLinks != null) {
            extractDeepLinks.configure {
                dependsOn(mExplodeTasks)
            }
        }
    }

    /**
     * copy data binding file must be do last in BundleTask, and reBundleTask will be package it.
     * @param bundleTask
     */
    private void processDataBinding(TaskProvider<Task> bundleTask) {
        bundleTask.configure {
            doLast {
                for (archiveLibrary in mAndroidArchiveLibraries) {
                    if (archiveLibrary.dataBindingFolder != null && archiveLibrary.dataBindingFolder.exists()) {
                        String filePath = "${mDirectoryManager.getReBundleDirectory().path}/${archiveLibrary.dataBindingFolder.name}"
                        new File(filePath).mkdirs()
                        mProject.copy {
                            from archiveLibrary.dataBindingFolder
                            into filePath
                        }
                    }

                    if (archiveLibrary.dataBindingLogFolder != null && archiveLibrary.dataBindingLogFolder.exists()) {
                        String filePath = "${mDirectoryManager.getReBundleDirectory().path}/${archiveLibrary.dataBindingLogFolder.name}"
                        new File(filePath).mkdirs()
                        mProject.copy {
                            from archiveLibrary.dataBindingLogFolder
                            into filePath
                        }
                    }
                }
            }
        }
    }

    static def getTaskDependencies(ResolvedArtifact artifact) {
        try {
            return artifact.id.publishArtifact.buildDependencies.getDependencies()
        } catch (MissingPropertyException ignore) {
            return Collections.emptySet()
        }
    }

    /**
     * exploded artifact files
     */
    private void processArtifacts(Collection<ResolvedArtifact> artifacts,
                                  TaskProvider<Task> prepareTask,
                                  TaskProvider<Task> bundleTask) {
        if (artifacts == null) {
            return
        }
        for (final ResolvedArtifact artifact in artifacts) {
            if (FatAarPlugin.ARTIFACT_TYPE_JAR == artifact.type) {
                addJarFile(artifact.file)
            } else if (FatAarPlugin.ARTIFACT_TYPE_AAR == artifact.type) {
                AndroidArchiveLibrary archiveLibrary = new AndroidArchiveLibrary(mProject, artifact, mVariant.name)
                addAndroidArchiveLibrary(archiveLibrary)
                Set<Task> dependencies = getTaskDependencies(artifact)

                final def zipFolder = archiveLibrary.getRootFolder()
                zipFolder.mkdirs()
                def group = artifact.getModuleVersion().id.group.capitalize()
                def name = artifact.name.capitalize()
                String taskName = "explode${group}${name}${mVariant.name.capitalize()}"
                Task explodeTask = mProject.tasks.create(taskName, Copy) {
                    from mProject.zipTree(artifact.file.absolutePath)
                    into zipFolder

                    doFirst {
                        // Delete previously extracted data.
                        zipFolder.deleteDir()
                    }
                }

                if (dependencies.size() == 0) {
                    explodeTask.dependsOn(prepareTask)
                } else {
                    explodeTask.dependsOn(dependencies.first())
                }
                Task javacTask = mVersionAdapter.getJavaCompileTask()
                javacTask.dependsOn(explodeTask)
                bundleTask.configure {
                    dependsOn(explodeTask)
                }
                mExplodeTasks.add(explodeTask)
            }
        }
    }

    /**
     * merge manifest
     */
    private void processManifest() {
        ManifestProcessorTask processManifestTask = mVersionAdapter.getProcessManifest()

        File manifestOutput
        if (FatUtils.compareVersion(VersionAdapter.AGPVersion, "8.3.0") >= 0) {
            manifestOutput = mProject.file(
                    "${mProject.buildDir.path}/intermediates/merged_manifest/${mVariant.name}/process${mVariant.name.capitalize()}Manifest/AndroidManifest.xml"
            )
        } else {
            manifestOutput = mProject.file(
                    "${mProject.buildDir.path}/intermediates/merged_manifest/${mVariant.name}/AndroidManifest.xml"
            )
        }

        final List<File> inputManifests = new ArrayList<>()
        for (archiveLibrary in mAndroidArchiveLibraries) {
            inputManifests.add(archiveLibrary.getManifest())
        }

        TaskProvider<LibraryManifestMerger> manifestsMergeTask = mProject
                .tasks.register("merge${mVariant.name.capitalize()}Manifest", LibraryManifestMerger) {
            if (FatUtils.compareVersion(VersionAdapter.AGPVersion, "8.13.0") >= 0) {
                withMergerFeatures(ManifestMerger2.Invoker.Feature.USES_SDK_IN_MANIFEST_LENIENT_HANDLING)
            }
            setGradleVersion(mProject.getGradle().getGradleVersion())
            setGradlePluginVersion(VersionAdapter.AGPVersion)
            setMainManifestFile(manifestOutput)
            setSecondaryManifestFiles(inputManifests)
            setOutputFile(manifestOutput)
        }

        processManifestTask.dependsOn(mExplodeTasks)
        processManifestTask.inputs.files(inputManifests)
        processManifestTask.doLast {
            // Merge manifests
            manifestsMergeTask.get().doTaskAction()
        }
    }

    private TaskProvider handleClassesMergeTask(final boolean isMinifyEnabled) {
        return mProject.tasks.register("mergeClasses" + mVariant.name.capitalize()) {
            outputs.upToDateWhen { false }

            dependsOn(mExplodeTasks)
            dependsOn(mVersionAdapter.getJavaCompileTask())
            try {
                // main lib maybe not use kotlin
                TaskProvider kotlinCompile = mProject.tasks.named("compile${mVariant.name.capitalize()}Kotlin")
                if (kotlinCompile != null) {
                    dependsOn(kotlinCompile)
                }
            } catch (Exception ignore) {
            }

            inputs.files(mAndroidArchiveLibraries.stream().map { it.classesJarFile }.collect())
                    .withPathSensitivity(PathSensitivity.RELATIVE)
            if (isMinifyEnabled) {
                inputs.files(mAndroidArchiveLibraries.stream().map { it.localJars }.collect())
                        .withPathSensitivity(PathSensitivity.RELATIVE)
                inputs.files(mJarFiles).withPathSensitivity(PathSensitivity.RELATIVE)
            }

            File mergeClassDir = mDirectoryManager.getMergeClassDirectory()
            File javacDir = mVersionAdapter.getClassPathDirFiles().first()

            outputs.dir(mergeClassDir)

            doFirst {
                // Extract relative paths and delete previous output.
                def pathsToDelete = new ArrayList<Path>()
                mProject.fileTree(mergeClassDir).forEach {
                    pathsToDelete.add(Paths.get(mergeClassDir.absolutePath).relativize(Paths.get(it.absolutePath)))
                }
                mergeClassDir.deleteDir()
                // Delete output files from javac dir.
                pathsToDelete.forEach {
                    Files.deleteIfExists(Paths.get("$javacDir.absolutePath/${it.toString()}"))
                }
            }

            doLast {
                ExplodedHelper.processClassesJarInfoClasses(mProject, mAndroidArchiveLibraries, mergeClassDir)
                if (isMinifyEnabled) {
                    ExplodedHelper.processLibsIntoClasses(mProject, mAndroidArchiveLibraries, mJarFiles, mergeClassDir)
                }

                mProject.copy {
                    from mergeClassDir
                    into javacDir
                    exclude 'META-INF/'
                }
            }
        }
    }

    private TaskProvider handleJarMergeTask(final TaskProvider syncLibTask) {
        final TaskProvider task = mProject.tasks.register("mergeJars" + mVariant.name.capitalize()) {
            dependsOn(mExplodeTasks)
            dependsOn(mVersionAdapter.getJavaCompileTask())
            mustRunAfter(syncLibTask)

            File aarMainJar = mDirectoryManager.getAarMainJarFile()
            File mergeClassDir = mDirectoryManager.getMergeClassDirectory()
            inputs.files(mAndroidArchiveLibraries.stream().map { it.libsFolder }.collect())
                    .withPathSensitivity(PathSensitivity.RELATIVE)
            inputs.files(mJarFiles).withPathSensitivity(PathSensitivity.RELATIVE)
            inputs.files(aarMainJar).withPathSensitivity(PathSensitivity.RELATIVE)
            inputs.dir(mergeClassDir).withPathSensitivity(PathSensitivity.RELATIVE)

            final def libsDir = mVersionAdapter.getLibsDirFile()
            File tempDir = mDirectoryManager.getAarMainClassesWithKotlinModulesDirectory()
            outputs.dir(libsDir)
            outputs.files(aarMainJar)
            outputs.dir(tempDir)

            doFirst {
                tempDir.deleteDir()
            }

            doLast {
                ExplodedHelper.processLibsIntoLibs(mProject, mAndroidArchiveLibraries, mJarFiles, libsDir)

                // Create a temporary directory
                tempDir.mkdirs()

                // Unzip original AGP-built 'classes.jar' (after syncLibJars) into tempDir
                if (aarMainJar.exists()) {
                    mProject.copy {
                        from mProject.zipTree(aarMainJar)
                        into tempDir
                    }
                }

                // Copy merged Kotlin META-INF into tempDir
                mProject.copy {
                    from mergeClassDir
                    into tempDir
                    include 'META-INF/*.kotlin_module'
                    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
                }

                // Re-zip and overwrite aar_main_jar/classes.jar
                aarMainJar.delete()
                mProject.ant.zip(destfile: aarMainJar) {
                    fileset(dir: tempDir)
                }
            }
        }
        return task
    }

    /**
     * merge classes and jars
     */
    private void processClassesAndJars(TaskProvider<Task> bundleTask) {
        boolean isMinifyEnabled = mVariant.getBuildType().isMinifyEnabled()

        final TaskProvider syncLibTask = mProject.tasks.named(mVersionAdapter.getSyncLibJarsTaskPath())
        final TaskProvider extractAnnotationsTask = mProject.tasks.named("extract${mVariant.name.capitalize()}Annotations")
        final TaskProvider transformClassesWithAsmTask = mProject.tasks.named(
                "transform${mVariant.name.capitalize()}ClassesWithAsm"
        )

        final TaskProvider mergeClassTask = handleClassesMergeTask(isMinifyEnabled)

        syncLibTask.configure {
            dependsOn(mergeClassTask)
            inputs.files(mAndroidArchiveLibraries.stream().map { it.libsFolder }.collect())
                    .withPathSensitivity(PathSensitivity.RELATIVE)
            inputs.files(mJarFiles).withPathSensitivity(PathSensitivity.RELATIVE)
        }
        extractAnnotationsTask.configure {
            mustRunAfter(mergeClassTask)
        }
        transformClassesWithAsmTask.configure {
            dependsOn(mergeClassTask)
        }

        if (!isMinifyEnabled) {
            TaskProvider mergeJars = handleJarMergeTask(syncLibTask)
            bundleTask.configure {
                dependsOn(mergeJars)
            }
        }
    }

    /**
     * merge R.txt (actually is to fix issue caused by provided configuration) and res
     *
     * Now the same res Id will cause a build exception: Duplicate resources, to encourage you to change res Id.
     * Adding "android.disableResourceValidation=true" to "gradle.properties" can do a trick to skip the exception, but is not recommended.
     */
    private void processResources() {
        String taskPath = "generate" + mVariant.name.capitalize() + "Resources"
        TaskProvider resourceGenTask = mProject.tasks.named(taskPath)
        if (resourceGenTask == null) {
            throw new RuntimeException("Can not find task ${taskPath}!")
        }

        resourceGenTask.configure {
            dependsOn(mExplodeTasks)
        }

        for (archiveLibrary in mAndroidArchiveLibraries) {
            FatUtils.logInfo("Merge resource，Library res：${archiveLibrary.resFolder}")
            mVariant.registerGeneratedResFolders(
                    mProject.files(archiveLibrary.resFolder)
            )
        }
    }

    /**
     * merge assets
     *
     * AaptOptions.setIgnoreAssets and AaptOptions.setIgnoreAssetsPattern will work as normal
     */
    private void processAssets() {
        Task assetsTask = mVersionAdapter.getMergeAssets()
        if (assetsTask == null) {
            throw new RuntimeException("Can not find task in variant.getMergeAssets()!")
        }

        assetsTask.dependsOn(mExplodeTasks)
        assetsTask.doFirst {
            mProject.android.sourceSets.each {
                if (it.name == mVariant.name) {
                    for (archiveLibrary in mAndroidArchiveLibraries) {
                        if (archiveLibrary.assetsFolder != null && archiveLibrary.assetsFolder.exists()) {
                            FatUtils.logInfo("Merge assets，Library assets folder：${archiveLibrary.assetsFolder}")
                            it.assets.srcDir(archiveLibrary.assetsFolder)
                        }
                    }
                }
            }
        }
    }

    /**
     * merge jniLibs
     */
    private void processJniLibs() {
        String taskPath = 'merge' + mVariant.name.capitalize() + 'JniLibFolders'
        TaskProvider mergeJniLibsTask = mProject.tasks.named(taskPath)
        if (mergeJniLibsTask == null) {
            throw new RuntimeException("Can not find task ${taskPath}!")
        }

        mergeJniLibsTask.configure {
            dependsOn(mExplodeTasks)

            doFirst {
                for (archiveLibrary in mAndroidArchiveLibraries) {
                    if (archiveLibrary.jniFolder != null && archiveLibrary.jniFolder.exists()) {
                        mProject.android.sourceSets.each {
                            if (it.name == mVariant.name) {
                                it.jniLibs.srcDir(archiveLibrary.jniFolder)
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * merge proguard.txt
     */
    private void processConsumerProguard() {
        String mergeTaskName = 'merge' + mVariant.name.capitalize() + 'ConsumerProguardFiles'
        TaskProvider mergeFileTask = mProject.tasks.named(mergeTaskName)
        if (mergeFileTask == null) {
            throw new RuntimeException("Can not find task ${mergeTaskName}!")
        }

        mergeFileTask.configure {
            dependsOn(mExplodeTasks)
            doLast {
                try {
                    Collection<File> files = mAndroidArchiveLibraries.stream().map { it.proguardRules }.collect()
                    File of
                    if (outputFile instanceof File) {
                        of = outputFile
                    } else {
                        // RegularFileProperty.class
                        of = outputFile.get().asFile
                    }
                    FatUtils.mergeFiles(files, of)
                } catch (Exception e) {
                    FatUtils.logAnytime(("If you see this error message, please submit issue to " +
                            "https://github.com/kezong/fat-aar-android/issues with version of AGP and Gradle. Thank you.")
                    )
                    e.printStackTrace()
                }
            }
        }
    }

    /**
     * merge consumer proguard to generate proguard
     * @since AGP 3.6
     */
    private void processGenerateProguard() {
        TaskProvider mergeGenerateProguardTask
        try {
            String mergeName = 'merge' + mVariant.name.capitalize() + 'GeneratedProguardFiles'
            mergeGenerateProguardTask = mProject.tasks.named(mergeName)
        } catch (Exception ignore) {
            return
        }

        mergeGenerateProguardTask.configure {
            dependsOn(mExplodeTasks)
            doLast {
                try {
                    Collection<File> files = mAndroidArchiveLibraries.stream().map { it.proguardRules }.collect()
                    File of
                    if (outputFile instanceof File) {
                        of = outputFile
                    } else {
                        // RegularFileProperty.class
                        of = outputFile.get().asFile
                    }
                    FatUtils.mergeFiles(files, of)
                } catch (Exception e) {
                    FatUtils.logAnytime(("If you see this error message, please submit issue to " +
                            "https://github.com/kezong/fat-aar-android/issues with version of AGP and Gradle. Thank you.")
                    )
                    e.printStackTrace()
                }
            }
        }
    }
}
