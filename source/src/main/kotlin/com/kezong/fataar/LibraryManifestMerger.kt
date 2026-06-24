package com.kezong.fataar

import com.android.build.gradle.internal.LoggerWrapper
import com.android.manifmerger.ManifestMerger2
import com.android.manifmerger.ManifestProvider
import com.android.manifmerger.MergingReport
import org.apache.tools.ant.BuildException
import org.gradle.api.DefaultTask
import org.gradle.work.DisableCachingByDefault
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.TaskAction
import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets

/**
 * ManifestMerger for Library
 */
@DisableCachingByDefault(because = "Merges directly into AGP's manifest output for the active variant.")
open class LibraryManifestMerger : DefaultTask() {

    @get:Internal
    var gradlePluginVersion: String? = null
    @get:Internal
    var gradleVersion: String? = null
    private var mergerFeatures: Array<out ManifestMerger2.Invoker.Feature>? = null
    @get:Internal
    var mainManifestFile: File? = null
    @get:Internal
    var secondaryManifestFiles: List<File>? = null
    @get:Internal
    var outputFile: File? = null

    fun withMergerFeatures(vararg features: ManifestMerger2.Invoker.Feature) {
        mergerFeatures = features
    }

    fun doTaskAction() {
        try {
            doFullTaskAction()
        } catch (e: Exception) {
            println("Gradle Plugin Version:$gradlePluginVersion")
            println("Gradle Version:$gradleVersion")
            throw RuntimeException(e.message)
        }
    }

    @TaskAction
    fun doFullTaskAction() {
        val iLogger = LoggerWrapper(logger)
        val mergerInvoker = ManifestMerger2.newMerger(
            mainManifestFile!!, iLogger, ManifestMerger2.MergeType.LIBRARY
        )

        val manifestProviders = mutableListOf<ManifestProvider>()
        secondaryManifestFiles?.forEach { file ->
            if (!file.exists()) return@forEach
            manifestProviders.add(object : ManifestProvider {
                override fun getManifest(): File = file.absoluteFile
                override fun getName(): String = file.name
            })
        }

        mergerInvoker.addManifestProviders(manifestProviders)
        mergerFeatures?.let { mergerInvoker.withFeatures(*it) }

        val mergingReport = mergerInvoker.merge()
        if (mergingReport.result.isError) {
            logger.error(mergingReport.reportString)
            mergingReport.log(iLogger)
            throw BuildException(mergingReport.reportString)
        }

        // fix utf-8 problem in windows
        BufferedWriter(
            OutputStreamWriter(FileOutputStream(outputFile!!), StandardCharsets.UTF_8)
        ).use { writer ->
            writer.append(
                mergingReport.getMergedDocument(MergingReport.MergedManifestKind.MERGED)
            )
            writer.flush()
        }
    }
}
