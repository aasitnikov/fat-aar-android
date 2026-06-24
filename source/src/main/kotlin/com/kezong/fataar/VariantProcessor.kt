package com.kezong.fataar

import com.android.manifmerger.ManifestMerger2
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.artifacts.ResolvedArtifact
import org.gradle.api.artifacts.ResolvedDependency
import org.gradle.api.file.DuplicatesStrategy
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.tasks.Copy
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskProvider
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * Core processor for variant
 */
class VariantProcessor(
    private val mProject: Project,
    private val mVariantInfo: VariantInfo,
    variantPackagesProperty: MapProperty<String, List<AndroidArchiveLibrary>>
) {
    private val mAndroidArchiveLibraries = mutableListOf<AndroidArchiveLibrary>()
    private val mAndroidArchiveLibrariesProperty: ListProperty<AndroidArchiveLibrary> =
        mProject.objects.listProperty(AndroidArchiveLibrary::class.java)
    private val mJarFiles = mutableListOf<File>()
    private val mExplodeTasks = mutableListOf<Task>()
    private val mDirectoryManager = DirectoryManager(mProject, mVariantInfo)

    init {
        variantPackagesProperty.put(mVariantInfo.name, mAndroidArchiveLibrariesProperty)
    }

    private fun addAndroidArchiveLibrary(library: AndroidArchiveLibrary) {
        mAndroidArchiveLibraries.add(library)
        mAndroidArchiveLibrariesProperty.add(library)
    }

    private fun addJarFile(jar: File) {
        mJarFiles.add(jar)
    }

    fun processVariant(
        artifacts: Collection<ResolvedArtifact>,
        flavorAars: Collection<FlavorArtifact.FlavorAar>,
        dependencies: Collection<ResolvedDependency>
    ) {
        val prepareTask = resolvePrepareTask()
            ?: throw RuntimeException("Can not find prepare task for variant ${mVariantInfo.name}!")
        val bundleTask = VersionAdapter.getBundleTaskProvider(mProject, mVariantInfo.name)

        preEmbed(artifacts, dependencies, prepareTask)
        processArtifacts(artifacts, prepareTask, bundleTask)
        processFlavorAars(flavorAars, bundleTask)
        processClassesAndJars(bundleTask)

        if (mAndroidArchiveLibraries.isEmpty()) return

        processManifest()
        processResources()
        processJniLibs()
        processConsumerProguard()
        processGenerateProguard()
        injectAssetsAndDataBinding(bundleTask)
        processDeepLinkTasks()
    }

    private fun resolvePrepareTask(): TaskProvider<Task>? {
        val capitalizedName = mVariantInfo.name.replaceFirstChar { it.uppercase() }
        val variantTaskPath = "pre${capitalizedName}Build"
        return try {
            mProject.tasks.named(variantTaskPath)
        } catch (_: Exception) {
            try {
                mProject.tasks.named("preBuild")
            } catch (_: Exception) {
                null
            }
        }
    }

    private fun preEmbed(
        artifacts: Collection<ResolvedArtifact>,
        dependencies: Collection<ResolvedDependency>,
        prepareTask: TaskProvider<Task>
    ) {
        val capitalizedName = mVariantInfo.name.replaceFirstChar { it.uppercase() }
        val embedTask = mProject.tasks.register("pre${capitalizedName}Embed") {
            doFirst {
                printEmbedArtifacts(artifacts, dependencies)
            }
        }
        prepareTask.configure { dependsOn(embedTask) }
    }

    private fun processDeepLinkTasks() {
        val capitalizedName = mVariantInfo.name.replaceFirstChar { it.uppercase() }
        val taskName = "extractDeepLinksForAar$capitalizedName"
        val extractDeepLinksForAar = mProject.tasks.named(taskName)
        extractDeepLinksForAar.configure { dependsOn(mExplodeTasks) }

        try {
            val extractDeepLinks = mProject.tasks.named("extractDeepLinks$capitalizedName")
            extractDeepLinks.configure { dependsOn(mExplodeTasks) }
        } catch (_: Exception) {
        }
    }

    private fun processArtifacts(
        artifacts: Collection<ResolvedArtifact>?,
        prepareTask: TaskProvider<Task>,
        bundleTask: TaskProvider<Task>
    ) {
        if (artifacts == null) return

        for (artifact in artifacts) {
            if (FatAarPlugin.ARTIFACT_TYPE_JAR == artifact.type) {
                addJarFile(artifact.file)
            } else if (FatAarPlugin.ARTIFACT_TYPE_AAR == artifact.type) {
                val archiveLibrary = AndroidArchiveLibrary(mProject, artifact, mVariantInfo.name)
                addAndroidArchiveLibrary(archiveLibrary)
                val dependencies = getTaskDependencies(artifact)

                val zipFolder = archiveLibrary.rootFolder
                zipFolder.mkdirs()
                val group = artifact.moduleVersion.id.group.replaceFirstChar { it.uppercase() }
                val name = artifact.name.replaceFirstChar { it.uppercase() }
                val capitalizedVariant = mVariantInfo.name.replaceFirstChar { it.uppercase() }
                val taskName = "explode${group}${name}$capitalizedVariant"

                val explodeTask = mProject.tasks.create(taskName, Copy::class.java) {
                    from(mProject.zipTree(artifact.file.absolutePath))
                    into(zipFolder)
                    // Enable incremental builds - only add input if file already exists
                    if (artifact.file.exists()) {
                        inputs.file(artifact.file).withPathSensitivity(PathSensitivity.NONE)
                    }
                    outputs.dir(zipFolder)
                    doFirst {
                        zipFolder.deleteRecursively()
                        // Ensure the artifact file exists before extraction
                        if (!artifact.file.exists()) {
                            throw IllegalStateException("Artifact file not found: ${artifact.file.absolutePath}. " +
                                    "Make sure embedded project dependencies are built before this task runs.")
                        }
                    }
                }

                if (dependencies.isEmpty()) {
                    explodeTask.dependsOn(prepareTask)
                    // For project dependencies, ensure the sub-project's bundle task runs first
                    val artifactProject = findArtifactProject(mProject, artifact)
                    if (artifactProject != null) {
                        try {
                            val bundleTask2 = VersionAdapter.getBundleTaskProvider(artifactProject, mVariantInfo.name)
                            explodeTask.dependsOn(bundleTask2)
                            FatUtils.logInfo("Added bundle dependency: ${bundleTask2.name} for ${artifact.moduleVersion.id}")
                        } catch (_: Exception) {
                            // Try build type only
                            try {
                                if (mVariantInfo.buildTypeName != null) {
                                    val bundleTask2 = VersionAdapter.getBundleTaskProvider(artifactProject, mVariantInfo.buildTypeName!!)
                                    explodeTask.dependsOn(bundleTask2)
                                }
                            } catch (_: Exception) {}
                        }
                    }
                } else {
                    // Add all task dependencies to ensure artifacts are built first
                    dependencies.forEach { explodeTask.dependsOn(it) }
                }
                val javacTask = VersionAdapter.getJavaCompileTask(mProject, mVariantInfo)
                javacTask.dependsOn(explodeTask)
                bundleTask.configure { dependsOn(explodeTask) }
                mExplodeTasks.add(explodeTask)
            }
        }
    }

    /**
     * Explode and embed sub-project aars that Gradle could not resolve directly (flavor /
     * cross-dimension case). The aar file is taken from the producing bundle task using only
     * stable public API, so no Gradle internal artifact types are involved.
     */
    private fun processFlavorAars(
        flavorAars: Collection<FlavorArtifact.FlavorAar>,
        bundleTask: TaskProvider<Task>
    ) {
        for (flavorAar in flavorAars) {
            val producingBundle = flavorAar.bundleTask
            val aarFile = resolveBundleArchiveFile(producingBundle.get())
            if (aarFile == null) {
                FatUtils.logError("[${mVariantInfo.name}]Can not locate bundle output for ${flavorAar.name}")
                continue
            }

            val archiveLibrary = AndroidArchiveLibrary(
                mProject, flavorAar.group, flavorAar.name, flavorAar.version, aarFile, mVariantInfo.name
            )
            addAndroidArchiveLibrary(archiveLibrary)

            val zipFolder = archiveLibrary.rootFolder
            zipFolder.mkdirs()
            val group = flavorAar.group.replaceFirstChar { it.uppercase() }
            val name = flavorAar.name.replaceFirstChar { it.uppercase() }
            val capitalizedVariant = mVariantInfo.name.replaceFirstChar { it.uppercase() }
            val taskName = "explode${group}${name}$capitalizedVariant"

            val explodeTask = mProject.tasks.create(taskName, Copy::class.java) {
                from(mProject.zipTree(aarFile))
                into(zipFolder)
                outputs.dir(zipFolder)
                doFirst {
                    zipFolder.deleteRecursively()
                    if (!aarFile.exists()) {
                        throw IllegalStateException(
                            "Artifact file not found: ${aarFile.absolutePath}. " +
                                    "Make sure the embedded project's bundle task runs before this task."
                        )
                    }
                }
            }
            explodeTask.dependsOn(producingBundle)

            val javacTask = VersionAdapter.getJavaCompileTask(mProject, mVariantInfo)
            javacTask.dependsOn(explodeTask)
            bundleTask.configure { dependsOn(explodeTask) }
            mExplodeTasks.add(explodeTask)
        }
    }

    /**
     * Read the aar file produced by a bundle task across AGP/Gradle versions (BundleAar extends
     * an archive task that exposes getArchiveFile()).
     */
    private fun resolveBundleArchiveFile(bundleTask: Task): File? {
        return try {
            val archiveFileProvider = bundleTask.javaClass.getMethod("getArchiveFile").invoke(bundleTask)
            val regularFile = archiveFileProvider.javaClass.getMethod("get").invoke(archiveFileProvider)
            regularFile.javaClass.getMethod("getAsFile").invoke(regularFile) as File
        } catch (e: Exception) {
            FatUtils.logInfo("Could not resolve bundle archive file: ${e.message}")
            null
        }
    }

    /**
     * Merge manifest
     */
    private fun processManifest() {
        val processManifestTask = VersionAdapter.getProcessManifest(mProject, mVariantInfo)
        val buildDir = mProject.layout.buildDirectory.get().asFile.path
        val capitalizedName = mVariantInfo.name.replaceFirstChar { it.uppercase() }

        val manifestOutput = if (FatUtils.compareVersion(VersionAdapter.agpVersion, "8.3.0") >= 0) {
            mProject.file(
                "$buildDir/intermediates/merged_manifest/${mVariantInfo.name}/process${capitalizedName}Manifest/AndroidManifest.xml"
            )
        } else {
            mProject.file(
                "$buildDir/intermediates/merged_manifest/${mVariantInfo.name}/AndroidManifest.xml"
            )
        }

        val inputManifests = mAndroidArchiveLibraries.map { it.manifest }

        val manifestsMergeTask = mProject.tasks.register(
            "merge${capitalizedName}Manifest",
            LibraryManifestMerger::class.java
        ) {
            if (FatUtils.compareVersion(VersionAdapter.agpVersion, "8.13.0") >= 0) {
                try {
                    val featureClass = ManifestMerger2.Invoker.Feature::class.java
                    val lenientFeature = featureClass.enumConstants.firstOrNull { it.name == "USES_SDK_IN_MANIFEST_LENIENT_HANDLING" }
                    if (lenientFeature != null) {
                        withMergerFeatures(lenientFeature)
                    }
                } catch (_: Exception) { }
            }
            gradleVersion = mProject.gradle.gradleVersion
            gradlePluginVersion = VersionAdapter.agpVersion
            mainManifestFile = manifestOutput
            secondaryManifestFiles = inputManifests
            outputFile = manifestOutput
        }

        processManifestTask.dependsOn(mExplodeTasks)
        processManifestTask.inputs.files(inputManifests)
        processManifestTask.doLast {
            manifestsMergeTask.get().doTaskAction()
        }
    }

    private fun handleClassesMergeTask(isMinifyEnabled: Boolean): TaskProvider<Task> {
        val capitalizedName = mVariantInfo.name.replaceFirstChar { it.uppercase() }
        return mProject.tasks.register("mergeClasses$capitalizedName") {
            // Enable up-to-date checks for better incremental builds
            dependsOn(mExplodeTasks)
            dependsOn(VersionAdapter.getJavaCompileTask(mProject, mVariantInfo))

            try {
                val kotlinCompile = mProject.tasks.named("compile${capitalizedName}Kotlin")
                dependsOn(kotlinCompile)
            } catch (_: Exception) {
            }

            inputs.files(mAndroidArchiveLibraries.map { it.classesJarFile })
                .withPathSensitivity(PathSensitivity.RELATIVE)
            if (isMinifyEnabled) {
                inputs.files(mAndroidArchiveLibraries.map { it.localJars })
                    .withPathSensitivity(PathSensitivity.RELATIVE)
                inputs.files(mJarFiles).withPathSensitivity(PathSensitivity.RELATIVE)
            }

            val mergeClassDir = mDirectoryManager.getMergeClassDirectory()
            val javacDir = VersionAdapter.getClassPathDirFiles(mProject, mVariantInfo).first()

            outputs.dir(mergeClassDir)
            outputs.dir(javacDir)

            doFirst {
                val pathsToDelete = mutableListOf<Path>()
                mProject.fileTree(mergeClassDir).forEach {
                    pathsToDelete.add(
                        Paths.get(mergeClassDir.absolutePath).relativize(Paths.get(it.absolutePath))
                    )
                }
                mergeClassDir.deleteRecursively()
                pathsToDelete.forEach {
                    Files.deleteIfExists(Paths.get("${javacDir.absolutePath}/${it}"))
                }
            }

            doLast {
                ExplodedHelper.processClassesJarInfoClasses(mProject, mAndroidArchiveLibraries, mergeClassDir)
                if (isMinifyEnabled) {
                    ExplodedHelper.processLibsIntoClasses(mProject, mAndroidArchiveLibraries, mJarFiles, mergeClassDir)
                }
                mProject.copy {
                    from(mergeClassDir)
                    into(javacDir)
                    exclude("META-INF/")
                }
            }
        }
    }

    private fun handleJarMergeTask(syncLibTask: TaskProvider<Task>): TaskProvider<Task> {
        val capitalizedName = mVariantInfo.name.replaceFirstChar { it.uppercase() }
        return mProject.tasks.register("mergeJars$capitalizedName") {
            dependsOn(mExplodeTasks)
            dependsOn(VersionAdapter.getJavaCompileTask(mProject, mVariantInfo))
            mustRunAfter(syncLibTask)

            val aarMainJar = mDirectoryManager.getAarMainJarFile()
            val mergeClassDir = mDirectoryManager.getMergeClassDirectory()
            inputs.files(mAndroidArchiveLibraries.map { it.libsFolder })
                .withPathSensitivity(PathSensitivity.RELATIVE)
            inputs.files(mJarFiles).withPathSensitivity(PathSensitivity.RELATIVE)
            inputs.files(aarMainJar).withPathSensitivity(PathSensitivity.RELATIVE)
            inputs.dir(mergeClassDir).withPathSensitivity(PathSensitivity.RELATIVE)

            val libsDir = VersionAdapter.getLibsDirFile(mProject, mVariantInfo)
            val tempDir = mDirectoryManager.getAarMainClassesWithKotlinModulesDirectory()
            outputs.dir(libsDir)
            outputs.files(aarMainJar)
            outputs.dir(tempDir)

            doFirst {
                tempDir.deleteRecursively()
            }

            doLast {
                ExplodedHelper.processLibsIntoLibs(mProject, mAndroidArchiveLibraries, mJarFiles, libsDir)

                tempDir.mkdirs()

                if (aarMainJar.exists()) {
                    mProject.copy {
                        from(mProject.zipTree(aarMainJar))
                        into(tempDir)
                    }
                }

                mProject.copy {
                    from(mergeClassDir)
                    into(tempDir)
                    include("META-INF/*.kotlin_module")
                    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
                }

                aarMainJar.delete()
                zipDirectory(tempDir, aarMainJar)
            }
        }
    }

    /**
     * Merge classes and jars
     */
    private fun processClassesAndJars(bundleTask: TaskProvider<Task>) {
        val isMinifyEnabled = mVariantInfo.minifyEnabled
        val capitalizedName = mVariantInfo.name.replaceFirstChar { it.uppercase() }

        val syncLibTask = mProject.tasks.named(VersionAdapter.getSyncLibJarsTaskPath(mVariantInfo))
        val extractAnnotationsTask = mProject.tasks.named("extract${capitalizedName}Annotations")
        val transformClassesWithAsmTask = mProject.tasks.named("transform${capitalizedName}ClassesWithAsm")

        val mergeClassTask = handleClassesMergeTask(isMinifyEnabled)

        syncLibTask.configure {
            dependsOn(mergeClassTask)
            inputs.files(mAndroidArchiveLibraries.map { it.libsFolder })
                .withPathSensitivity(PathSensitivity.RELATIVE)
            inputs.files(mJarFiles).withPathSensitivity(PathSensitivity.RELATIVE)
        }
        extractAnnotationsTask.configure { mustRunAfter(mergeClassTask) }
        transformClassesWithAsmTask.configure { dependsOn(mergeClassTask) }

        if (!isMinifyEnabled) {
            val mergeJars = handleJarMergeTask(syncLibTask)
            bundleTask.configure { dependsOn(mergeJars) }
        }
    }

    /**
     * Find the matching source set for this variant using reflection (cached per call)
     */
    private fun findVariantSourceSet(): Any? {
        val android = mProject.extensions.getByName("android")
        val sourceSets = android.javaClass.getMethod("getSourceSets").invoke(android) as Iterable<*>
        for (sourceSet in sourceSets) {
            val ssName = sourceSet!!.javaClass.getMethod("getName").invoke(sourceSet) as String
            if (ssName == mVariantInfo.name) {
                return sourceSet
            }
        }
        return null
    }

    /**
     * Merge R.txt and res
     */
    private fun processResources() {
        val capitalizedName = mVariantInfo.name.replaceFirstChar { it.uppercase() }
        val taskPath = "generate${capitalizedName}Resources"
        val resourceGenTask = mProject.tasks.named(taskPath)
        resourceGenTask.configure {
            dependsOn(mExplodeTasks)
            doFirst {
                val sourceSet = findVariantSourceSet() ?: return@doFirst
                val res = sourceSet.javaClass.getMethod("getRes").invoke(sourceSet)
                val srcDirMethod = res.javaClass.getMethod("srcDir", Any::class.java)
                for (archiveLibrary in mAndroidArchiveLibraries) {
                    if (archiveLibrary.resFolder.exists()) {
                        FatUtils.logInfo("Merge resource, Library res: ${archiveLibrary.resFolder}")
                        srcDirMethod.invoke(res, archiveLibrary.resFolder)
                    }
                }
            }
        }
    }

    /**
     * Merge assets
     */

    /**
     * Merge jniLibs
     */
    private fun processJniLibs() {
        val capitalizedName = mVariantInfo.name.replaceFirstChar { it.uppercase() }
        val taskPath = "merge${capitalizedName}JniLibFolders"
        val mergeJniLibsTask = mProject.tasks.named(taskPath)

        mergeJniLibsTask.configure {
            dependsOn(mExplodeTasks)
            doFirst {
                val sourceSet = findVariantSourceSet() ?: return@doFirst
                val jniLibs = sourceSet.javaClass.getMethod("getJniLibs").invoke(sourceSet)
                val srcDirMethod = jniLibs.javaClass.getMethod("srcDir", Any::class.java)
                for (archiveLibrary in mAndroidArchiveLibraries) {
                    if (archiveLibrary.jniFolder.exists()) {
                        srcDirMethod.invoke(jniLibs, archiveLibrary.jniFolder)
                    }
                }
            }
        }
    }

    /**
     * Merge consumer proguard rules (proguard.txt) from embedded libraries into the
     * variant's merged consumer proguard file so they ship inside the final aar.
     */
    private fun processConsumerProguard() {
        val capitalizedName = mVariantInfo.name.replaceFirstChar { it.uppercase() }
        val mergeTaskName = "merge${capitalizedName}ConsumerProguardFiles"
        val mergeFileTask = try {
            mProject.tasks.named(mergeTaskName)
        } catch (_: Exception) {
            return
        }
        mergeFileTask.configure {
            dependsOn(mExplodeTasks)
            val task = this
            doLast {
                mergeProguardInto(task)
            }
        }
    }

    /**
     * Merge generated proguard rules.
     * @since AGP 3.6
     */
    private fun processGenerateProguard() {
        val capitalizedName = mVariantInfo.name.replaceFirstChar { it.uppercase() }
        val mergeName = "merge${capitalizedName}GeneratedProguardFiles"
        val mergeGenerateProguardTask = try {
            mProject.tasks.named(mergeName)
        } catch (_: Exception) {
            return
        }
        mergeGenerateProguardTask.configure {
            dependsOn(mExplodeTasks)
            val task = this
            doLast {
                mergeProguardInto(task)
            }
        }
    }

    private fun mergeProguardInto(task: Task) {
        try {
            val outputFile = resolveTaskOutputFile(task) ?: return
            val existing = if (outputFile.exists()) outputFile.readText(Charsets.UTF_8) else ""
            val toAppend = StringBuilder()
            for (archiveLibrary in mAndroidArchiveLibraries) {
                val proguardFile = archiveLibrary.proguardRules
                if (!proguardFile.exists()) continue
                val content = proguardFile.readText(Charsets.UTF_8).trim()
                if (content.isEmpty()) continue
                // Idempotent: don't re-append rules that are already present (handles both the
                // AGP-merged base and repeated incremental builds).
                if (existing.contains(content) || toAppend.contains(content)) continue
                toAppend.append('\n').append(content).append('\n')
            }
            if (toAppend.isNotEmpty()) {
                if (!outputFile.exists()) outputFile.createNewFile()
                outputFile.appendText(toAppend.toString())
            }
        } catch (e: Exception) {
            FatUtils.logAnytime(
                "If you see this error message, please submit issue to " +
                        "https://github.com/kezong/fat-aar-android/issues with version of AGP and Gradle. Thank you."
            )
            e.printStackTrace()
        }
    }

    /**
     * Merge assets and data-binding artifacts from embedded libraries into the final aar.
     *
     * The new Variant API pipeline lets AGP build the aar natively, so there is no re-bundle
     * step and per-AGP source-set/merge-task timing is unreliable across versions. We instead
     * inject the embedded libraries' "assets", "data-binding" and "data-binding-base-class-log"
     * folders straight into the produced aar after the bundle task finishes. This is version
     * agnostic (pure zip manipulation) and never fails the build.
     */
    private fun injectAssetsAndDataBinding(bundleTask: TaskProvider<Task>) {
        bundleTask.configure {
            val task = this
            doLast {
                try {
                    val folders = mutableListOf<Pair<File, String>>()
                    for (archiveLibrary in mAndroidArchiveLibraries) {
                        // assets merge — version-agnostic: inject embedded assets straight into
                        // the produced aar instead of fighting per-AGP source-set/merge timing.
                        if (archiveLibrary.assetsFolder.exists()) {
                            folders.add(archiveLibrary.assetsFolder to "assets")
                        }
                        if (archiveLibrary.dataBindingFolder.exists()) {
                            folders.add(archiveLibrary.dataBindingFolder to archiveLibrary.dataBindingFolder.name)
                        }
                        if (archiveLibrary.dataBindingLogFolder.exists()) {
                            folders.add(archiveLibrary.dataBindingLogFolder to archiveLibrary.dataBindingLogFolder.name)
                        }
                    }
                    if (folders.isEmpty()) return@doLast

                    val aarFiles = findOutputAars(task)
                    if (aarFiles.isEmpty()) {
                        FatUtils.logInfo("[fat-aar] no output aar found for ${mVariantInfo.name}")
                        return@doLast
                    }
                    aarFiles.forEach { aar ->
                        FatUtils.logInfo("[fat-aar] injecting ${folders.size} folder(s) into ${aar.absolutePath}")
                        injectFoldersIntoAar(aar, folders)
                    }
                } catch (e: Exception) {
                    FatUtils.logAnytime(
                        "If you see this error message, please submit issue to " +
                                "https://github.com/kezong/fat-aar-android/issues with version of AGP and Gradle. Thank you."
                    )
                    e.printStackTrace()
                }
            }
        }
    }

    /**
     * Locate the aar(s) produced for this variant. Primary source is the bundle task's own
     * archive file (exact across AGP versions); falls back to scanning the standard locations.
     */
    private fun findOutputAars(bundleTask: Task): List<File> {
        val result = mutableListOf<File>()

        // 1. Exact file from the bundle task itself (BundleAar extends an archive task).
        try {
            val archiveFileProvider = bundleTask.javaClass.getMethod("getArchiveFile").invoke(bundleTask)
            val regularFile = archiveFileProvider.javaClass.getMethod("get").invoke(archiveFileProvider)
            val file = regularFile.javaClass.getMethod("getAsFile").invoke(regularFile) as File
            if (file.exists()) result.add(file)
        } catch (e: Exception) {
            FatUtils.logInfo("[data-binding] could not read bundle archive file: ${e.message}")
        }

        // 2. Fallbacks: documented intermediate + outputs/aar (match on normalized variant name).
        val intermediate = VersionAdapter.outputFile(mProject, mVariantInfo.name)
        if (intermediate.exists()) result.add(intermediate)

        val normalizedVariant = mVariantInfo.name.replace("-", "").lowercase()
        val outputsAarDir = File(mProject.layout.buildDirectory.get().asFile, "outputs/aar")
        outputsAarDir.listFiles { f ->
            f.isFile && f.name.endsWith(".aar") &&
                    f.name.replace("-", "").lowercase().contains(normalizedVariant)
        }?.let { result.addAll(it) }

        return result.distinctBy { it.absolutePath }
    }

    private fun resolveTaskOutputFile(task: Task): File? {
        val output = task.javaClass.getMethod("getOutputFile").invoke(task) ?: return null
        return when (output) {
            is File -> output
            else -> {
                val provider = output.javaClass.getMethod("get").invoke(output)
                provider.javaClass.getMethod("getAsFile").invoke(provider) as File
            }
        }
    }

    companion object {
        private const val ZIP_BUFFER_SIZE = 8192

        /**
         * Find the project that produced this artifact (for project dependencies)
         */
        private fun findArtifactProject(project: Project, artifact: ResolvedArtifact): Project? {
            val group = artifact.moduleVersion.id.group
            val name = artifact.moduleVersion.id.name
            for (p in project.rootProject.allprojects) {
                if (name == p.name && group == p.group.toString()) {
                    return p
                }
            }
            return null
        }

        private fun zipDirectory(sourceDir: File, zipFile: File) {
            ZipOutputStream(BufferedOutputStream(FileOutputStream(zipFile), ZIP_BUFFER_SIZE)).use { zos ->
                val buffer = ByteArray(ZIP_BUFFER_SIZE)
                sourceDir.walkTopDown().filter { it.isFile }.forEach { file ->
                    val entryName = file.relativeTo(sourceDir).path.replace("\\", "/")
                    zos.putNextEntry(ZipEntry(entryName))
                    file.inputStream().buffered(ZIP_BUFFER_SIZE).use { input ->
                        var len: Int
                        while (input.read(buffer).also { len = it } > 0) {
                            zos.write(buffer, 0, len)
                        }
                    }
                    zos.closeEntry()
                }
            }
        }

        /**
         * Repackage [aarFile] adding the contents of each (folder -> entryPrefix) pair.
         * Existing entries are preserved and never overwritten.
         */
        private fun injectFoldersIntoAar(aarFile: File, folders: List<Pair<File, String>>) {
            val tempAar = File(aarFile.parentFile, "${aarFile.name}.fataar.tmp")
            val existingEntries = mutableSetOf<String>()
            ZipOutputStream(BufferedOutputStream(FileOutputStream(tempAar), ZIP_BUFFER_SIZE)).use { zos ->
                ZipFile(aarFile).use { zip ->
                    val entries = zip.entries()
                    while (entries.hasMoreElements()) {
                        val entry = entries.nextElement()
                        existingEntries.add(entry.name)
                        zos.putNextEntry(ZipEntry(entry.name))
                        if (!entry.isDirectory) {
                            zip.getInputStream(entry).use { it.copyTo(zos, ZIP_BUFFER_SIZE) }
                        }
                        zos.closeEntry()
                    }
                }
                for ((folder, prefix) in folders) {
                    folder.walkTopDown().filter { it.isFile }.forEach { file ->
                        val relative = file.relativeTo(folder).path.replace("\\", "/")
                        val entryName = "$prefix/$relative"
                        if (existingEntries.contains(entryName)) return@forEach
                        existingEntries.add(entryName)
                        zos.putNextEntry(ZipEntry(entryName))
                        file.inputStream().use { it.copyTo(zos, ZIP_BUFFER_SIZE) }
                        zos.closeEntry()
                    }
                }
            }
            if (aarFile.delete()) {
                tempAar.renameTo(aarFile)
            } else {
                tempAar.delete()
                throw RuntimeException("Could not replace aar file: ${aarFile.absolutePath}")
            }
        }

        private fun printEmbedArtifacts(
            artifacts: Collection<ResolvedArtifact>,
            dependencies: Collection<ResolvedDependency>
        ) {
            val moduleNames = artifacts.map { it.moduleVersion.id.name }.toMutableList()
            for (dependency in dependencies) {
                if (!moduleNames.contains(dependency.moduleName)) continue

                val self = dependency.allModuleArtifacts.find { module ->
                    module.moduleVersion.id.name == dependency.moduleName
                } ?: continue

                FatUtils.logAnytime("[embed detected][${self.type}]${self.moduleVersion.id}")
                moduleNames.remove(self.moduleVersion.id.name)

                for (artifact in dependency.allModuleArtifacts) {
                    if (!moduleNames.contains(artifact.moduleVersion.id.name)) continue
                    if (artifact != self) {
                        FatUtils.logAnytime("    - [embed detected][transitive][${artifact.type}]${artifact.moduleVersion.id}")
                        moduleNames.remove(artifact.moduleVersion.id.name)
                    }
                }
            }

            for (name in moduleNames) {
                val artifact = artifacts.find { it.moduleVersion.id.name == name }
                if (artifact != null) {
                    FatUtils.logAnytime("[embed detected][${artifact.type}]${artifact.moduleVersion.id}")
                }
            }
        }

        private fun getTaskDependencies(artifact: ResolvedArtifact): Set<Task> {
            return try {
                val publishArtifact = artifact.javaClass.getMethod("getId").invoke(artifact)
                val pa = publishArtifact.javaClass.getMethod("getPublishArtifact").invoke(publishArtifact)
                val bd = pa.javaClass.getMethod("getBuildDependencies").invoke(pa)
                // TaskDependency.getDependencies(Task) takes one (nullable) argument; older code
                // paths may expose a zero-arg variant. Support both.
                val getDeps = bd.javaClass.methods.firstOrNull { it.name == "getDependencies" && it.parameterCount == 1 }
                    ?: bd.javaClass.methods.firstOrNull { it.name == "getDependencies" && it.parameterCount == 0 }
                @Suppress("UNCHECKED_CAST")
                val tasks = when (getDeps?.parameterCount) {
                    1 -> getDeps.invoke(bd, null) as? Set<Task>
                    0 -> getDeps.invoke(bd) as? Set<Task>
                    else -> null
                } ?: emptySet()
                FatUtils.logInfo("Found ${tasks.size} build dependencies for ${artifact.moduleVersion.id}")
                tasks
            } catch (e: Exception) {
                FatUtils.logInfo("Could not get build dependencies for ${artifact.moduleVersion.id}: ${e.message}")
                emptySet()
            }
        }
    }
}
