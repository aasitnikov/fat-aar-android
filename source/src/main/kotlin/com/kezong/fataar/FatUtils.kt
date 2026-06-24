package com.kezong.fataar

import org.gradle.api.Project

object FatUtils {

    // FIXME: be careful, this is a static field! Logs can be mixed up if multiple tasks are running concurrently
    private lateinit var sProject: Project

    fun attach(p: Project) {
        sProject = p
    }

    fun logError(msg: Any?) {
        sProject.logger.error("[fat-aar]$msg")
    }

    fun logError(msg: Any?, err: Throwable) {
        sProject.logger.error("[fat-aar]$msg", err)
    }

    fun logInfo(msg: Any?) {
        sProject.logger.info("[fat-aar]$msg")
    }

    fun logAnytime(msg: Any?) {
        sProject.logger.lifecycle("[fat-aar]$msg")
    }

    fun showDir(indent: Int, file: java.io.File) {
        repeat(indent) { print('-') }
        println("${file.name} ${file.length()}")
        if (file.isDirectory) {
            file.listFiles()?.forEach { showDir(indent + 4, it) }
        }
    }

    fun deleteEmptyDir(file: java.io.File) {
        file.listFiles()?.forEach { x ->
            if (x.isDirectory) {
                if ((x.listFiles()?.size ?: 0) == 0) {
                    x.delete()
                } else {
                    deleteEmptyDir(x)
                    if ((x.listFiles()?.size ?: 0) == 0) {
                        x.delete()
                    }
                }
            }
        }
    }

    fun compareVersion(v1: String, v2: String): Int {
        if (v1 == v2) return 0

        val version1 = v1.split("-")
        val version2 = v2.split("-")
        val version1Array = version1[0].split("[._]".toRegex())
        val version2Array = version2[0].split("[._]".toRegex())

        val preRelease1 = if (version1.size > 1) version1[1] else ""
        val preRelease2 = if (version2.size > 1) version2[1] else ""

        var index = 0
        val minLen = minOf(version1Array.size, version2Array.size)
        var diff = 0L

        while (index < minLen) {
            diff = version1Array[index].toLong() - version2Array[index].toLong()
            if (diff != 0L) break
            index++
        }

        if (diff == 0L) {
            for (i in index until version1Array.size) {
                if (version1Array[i].toLong() > 0) return 1
            }
            for (i in index until version2Array.size) {
                if (version2Array[i].toLong() > 0) return -1
            }
            // compare pre-release
            return when {
                preRelease1.isNotEmpty() && preRelease2.isEmpty() -> -1
                preRelease1.isEmpty() && preRelease2.isNotEmpty() -> 1
                preRelease1.isNotEmpty() && preRelease2.isNotEmpty() -> preRelease1.compareTo(preRelease2).let {
                    when {
                        it > 0 -> 1
                        it < 0 -> -1
                        else -> 0
                    }
                }
                else -> 0
            }
        }
        return if (diff > 0) 1 else -1
    }

    fun formatDataSize(size: Long): String {
        return when {
            size < 1024 -> "${size}Byte"
            size < 1024 * 1024 -> String.format("%.0fK", size / 1024.0)
            size < 1024 * 1024 * 1024 -> String.format("%.2fM", size / (1024 * 1024.0))
            else -> String.format("%.2fG", size / (1024 * 1024 * 1024.0))
        }
    }

    fun mergeFiles(inputFiles: Collection<java.io.File>?, output: java.io.File) {
        if (inputFiles == null) return
        val existingFiles = inputFiles.filter { it.exists() }
        if (existingFiles.isEmpty()) return

        if (!output.exists()) {
            output.createNewFile()
        }

        for (file in existingFiles) {
            output.appendText("\n${file.readText(Charsets.UTF_8)}\n")
        }
    }
}
