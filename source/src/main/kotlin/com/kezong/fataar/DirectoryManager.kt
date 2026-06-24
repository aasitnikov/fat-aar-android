package com.kezong.fataar

import org.gradle.api.Project

class DirectoryManager(
    private val project: Project,
    private val variantInfo: VariantInfo
) {
    companion object {
        private const val RE_BUNDLE_FOLDER = "aar_rebundle"
        private const val INTERMEDIATES_TEMP_FOLDER = "fat-aar"
    }

    fun getReBundleDirectory(): java.io.File {
        return project.layout.buildDirectory
            .dir("outputs/$RE_BUNDLE_FOLDER/${variantInfo.name}")
            .get().asFile
    }

    fun getMergeClassDirectory(): java.io.File {
        return project.layout.buildDirectory
            .dir("intermediates/$INTERMEDIATES_TEMP_FOLDER/merge_classes/${variantInfo.name}")
            .get().asFile
    }

    fun getAarMainJarFile(): java.io.File {
        val relativePath = if (FatUtils.compareVersion(VersionAdapter.agpVersion, "8.3.0") >= 0) {
            "intermediates/aar_main_jar/${variantInfo.name}/sync${variantInfo.name.replaceFirstChar { it.uppercase() }}LibJars/classes.jar"
        } else {
            "intermediates/aar_main_jar/${variantInfo.name}/classes.jar"
        }
        return project.layout.buildDirectory.file(relativePath).get().asFile
    }

    fun getAarMainClassesWithKotlinModulesDirectory(): java.io.File {
        return project.layout.buildDirectory
            .dir("intermediates/$INTERMEDIATES_TEMP_FOLDER/aar_main_classes_with_kotlin_modules/${variantInfo.name}")
            .get().asFile
    }
}
