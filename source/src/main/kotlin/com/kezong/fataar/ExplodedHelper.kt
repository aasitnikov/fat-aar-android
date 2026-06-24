package com.kezong.fataar

import org.gradle.api.Project
import java.io.File

object ExplodedHelper {

    fun processLibsIntoLibs(
        project: Project,
        androidLibraries: Collection<AndroidArchiveLibrary>,
        jarFiles: Collection<File>,
        folderOut: File
    ) {
        for (androidLibrary in androidLibraries) {
            if (!androidLibrary.rootFolder.exists()) {
                FatUtils.logInfo("[warning]${androidLibrary.rootFolder} not found!")
                continue
            }
            if (androidLibrary.localJars.isEmpty()) {
                FatUtils.logInfo("Not found jar file, Library: ${androidLibrary.name}")
            } else {
                FatUtils.logInfo("Merge ${androidLibrary.name} local jar files")
                project.copy {
                    from(androidLibrary.localJars)
                    into(folderOut)
                }
            }
        }
        for (jarFile in jarFiles) {
            if (jarFile.exists()) {
                FatUtils.logInfo("Copy jar from: $jarFile to ${folderOut.absolutePath}")
                project.copy {
                    from(jarFile)
                    into(folderOut)
                }
            } else {
                FatUtils.logInfo("[warning]$jarFile not found!")
            }
        }
    }

    fun processClassesJarInfoClasses(
        project: Project,
        androidLibraries: Collection<AndroidArchiveLibrary>,
        folderOut: File
    ) {
        FatUtils.logInfo("Merge ClassesJar")
        for (androidLibrary in androidLibraries) {
            val jarFile = androidLibrary.classesJarFile
            if (jarFile.exists()) {
                project.copy {
                    from(project.zipTree(jarFile))
                    into(folderOut)
                }
            }
        }
    }

    fun processLibsIntoClasses(
        project: Project,
        androidLibraries: Collection<AndroidArchiveLibrary>,
        jarFiles: Collection<File>,
        folderOut: File
    ) {
        FatUtils.logInfo("Merge Libs")
        val allJarFiles = mutableListOf<File>()
        for (androidLibrary in androidLibraries) {
            if (!androidLibrary.rootFolder.exists()) {
                FatUtils.logInfo("[warning]${androidLibrary.rootFolder} not found!")
                continue
            }
            FatUtils.logInfo("[androidLibrary]${androidLibrary.name}")
            allJarFiles.addAll(androidLibrary.localJars)
        }
        for (jarFile in jarFiles) {
            if (jarFile.exists()) {
                allJarFiles.add(jarFile)
            }
        }
        for (jarFile in allJarFiles) {
            project.copy {
                from(project.zipTree(jarFile))
                into(folderOut)
                exclude("META-INF/")
            }
        }
    }
}
