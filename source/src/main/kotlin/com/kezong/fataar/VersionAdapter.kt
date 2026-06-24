package com.kezong.fataar

import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.UnknownTaskException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.tasks.TaskProvider
import java.lang.reflect.Field

object VersionAdapter {

    val agpVersion: String by lazy {
        try {
            val aClass = Class.forName("com.android.Version")
            val version: Field = aClass.getDeclaredField("ANDROID_GRADLE_PLUGIN_VERSION")
            version.get(aClass) as String
        } catch (_: Throwable) {
            val aClass = Class.forName("com.android.builder.model.Version")
            val version: Field = aClass.getDeclaredField("ANDROID_GRADLE_PLUGIN_VERSION")
            version.get(aClass) as String
        }
    }

    fun getClassPathDirFiles(project: Project, variantInfo: VariantInfo): ConfigurableFileCollection {
        val buildDir = project.layout.buildDirectory.get().asFile.path
        val variantName = variantInfo.name
        val capitalizedName = variantName.replaceFirstChar { it.uppercase() }
        return if (FatUtils.compareVersion(agpVersion, "8.3.0") >= 0) {
            project.files("$buildDir/intermediates/javac/$variantName/compile${capitalizedName}JavaWithJavac/classes")
        } else {
            project.files("$buildDir/intermediates/javac/$variantName/classes")
        }
    }

    fun getLibsDirFile(project: Project, variantInfo: VariantInfo): java.io.File {
        val buildDir = project.layout.buildDirectory.get().asFile.path
        val capitalizedName = variantInfo.name.replaceFirstChar { it.uppercase() }
        return if (FatUtils.compareVersion(agpVersion, "8.3.0") >= 0) {
            project.file(
                "$buildDir/intermediates/aar_libs_directory/${variantInfo.name}/sync${capitalizedName}LibJars/libs"
            )
        } else {
            project.file("$buildDir/intermediates/aar_libs_directory/${variantInfo.name}/libs")
        }
    }

    fun getJavaCompileTask(project: Project, variantInfo: VariantInfo): Task {
        val capitalizedName = variantInfo.name.replaceFirstChar { it.uppercase() }
        return project.tasks.named("compile${capitalizedName}JavaWithJavac").get()
    }

    fun getProcessManifest(project: Project, variantInfo: VariantInfo): Task {
        val capitalizedName = variantInfo.name.replaceFirstChar { it.uppercase() }
        return project.tasks.named("process${capitalizedName}Manifest").get()
    }

    fun getMergeAssets(project: Project, variantInfo: VariantInfo): Task {
        val capitalizedName = variantInfo.name.replaceFirstChar { it.uppercase() }
        return project.tasks.named("merge${capitalizedName}Assets").get()
    }

    fun getSyncLibJarsTaskPath(variantInfo: VariantInfo): String {
        val capitalizedName = variantInfo.name.replaceFirstChar { it.uppercase() }
        return "sync${capitalizedName}LibJars"
    }

    fun getBundleTaskProvider(project: Project, variantName: String): TaskProvider<Task> {
        val capitalizedName = variantName.replaceFirstChar { it.uppercase() }
        val taskPath = "bundle$capitalizedName"
        return try {
            project.tasks.named(taskPath)
        } catch (_: UnknownTaskException) {
            project.tasks.named("${taskPath}Aar")
        }
    }

    fun outputFile(project: Project, variantName: String): java.io.File {
        val buildDir = project.layout.buildDirectory.get().asFile.path
        val capitalizedName = variantName.replaceFirstChar { it.uppercase() }
        return if (FatUtils.compareVersion(agpVersion, "8.4.0") >= 0) {
            project.file("$buildDir/intermediates/aar/$variantName/bundle${capitalizedName}Aar/output.aar")
        } else {
            project.file("$buildDir/outputs/aar/${project.name}-$variantName.aar")
        }
    }
}
