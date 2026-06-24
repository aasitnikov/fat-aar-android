package com.kezong.fataar

import org.gradle.api.Project
import org.gradle.api.artifacts.ResolvedArtifact
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Describes an exploded aar dependency. It is intentionally built only from stable, public
 * data (coordinates + the aar file), so it can be created either from a resolved artifact or
 * directly from a sub-project's bundle output (the flavor/cross-dimension case) without ever
 * touching Gradle internal artifact types. This keeps a single compiled plugin working from
 * AGP 8.3 (Gradle 8.4) up to the latest.
 */
class AndroidArchiveLibrary(
    private val mProject: Project,
    val group: String,
    val name: String,
    val version: String,
    val artifactFile: File,
    private val mVariantName: String
) {
    private var mPackageName: String? = null

    constructor(project: Project, artifact: ResolvedArtifact, variantName: String) : this(
        project,
        artifact.moduleVersion.id.group,
        artifact.moduleVersion.id.name,
        artifact.moduleVersion.id.version,
        artifact.file,
        variantName
    ) {
        require(artifact.type == "aar") { "artifact must be aar type!" }
    }

    val rootFolder: File
        get() {
            val explodedRootDir = mProject.file(
                "${mProject.layout.buildDirectory.get().asFile}/intermediates/exploded-aar/"
            )
            return mProject.file(
                "$explodedRootDir/$group/$name/$version/$mVariantName"
            )
        }

    val aidlFolder: File get() = File(rootFolder, "aidl")
    val assetsFolder: File get() = File(rootFolder, "assets")
    val libsFolder: File get() = File(rootFolder, "libs")
    val classesJarFile: File get() = File(rootFolder, "classes.jar")

    val localJars: Collection<File>
        get() {
            val jarList = libsFolder.listFiles() ?: return emptyList()
            return jarList.filter { it.isFile && it.name.endsWith(".jar") }
        }

    val jniFolder: File get() = File(rootFolder, "jni")
    val resFolder: File get() = File(rootFolder, "res")
    val manifest: File get() = File(rootFolder, "AndroidManifest.xml")
    val lintJar: File get() = File(rootFolder, "lint.jar")
    val proguardRules: File get() = File(rootFolder, "proguard.txt")
    val symbolFile: File get() = File(rootFolder, "R.txt")
    val dataBindingFolder: File get() = File(rootFolder, "data-binding")
    val dataBindingLogFolder: File get() = File(rootFolder, "data-binding-base-class-log")

    @Synchronized
    fun getPackageName(): String {
        if (mPackageName == null) {
            val manifestFile = manifest
            if (manifestFile.exists()) {
                try {
                    val dbf = DocumentBuilderFactory.newInstance()
                    val doc = dbf.newDocumentBuilder().parse(manifestFile)
                    val element: Element = doc.documentElement
                    mPackageName = element.getAttribute("package")
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            } else {
                throw RuntimeException("$name module's AndroidManifest not found")
            }
        }
        return mPackageName!!
    }
}
