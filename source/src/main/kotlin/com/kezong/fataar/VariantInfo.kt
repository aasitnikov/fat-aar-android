package com.kezong.fataar

import com.android.build.api.variant.Variant
import org.gradle.api.NamedDomainObjectContainer
import org.gradle.api.Project

data class VariantInfo(
    val name: String,
    val buildTypeName: String?,
    val flavorName: String?,
    val minifyEnabled: Boolean
) {
    companion object {
        @JvmStatic
        fun fromNew(variant: Variant, project: Project): VariantInfo {
            return VariantInfo(
                name = variant.name,
                buildTypeName = variant.buildType,
                flavorName = variant.flavorName,
                minifyEnabled = resolveMinifyEnabled(variant, project)
            )
        }

        /**
         * Detect whether minification (R8/ProGuard) is enabled for this variant.
         * Works across AGP 8.3 - 9.x:
         *  - AGP 9+ may expose `isMinifyEnabled()` directly on the Variant.
         *  - On AGP 8.x the new Variant API has no such accessor, so we fall back to the
         *    build type DSL (`android.buildTypes.<name>.isMinifyEnabled`).
         */
        private fun resolveMinifyEnabled(variant: Variant, project: Project): Boolean {
            try {
                val method = variant.javaClass.getMethod("isMinifyEnabled")
                return method.invoke(variant) as Boolean
            } catch (_: Exception) {
            }
            try {
                val buildTypeName = variant.buildType ?: return false
                val android = project.extensions.getByName("android")
                val buildTypes = android.javaClass.getMethod("getBuildTypes").invoke(android)
                        as NamedDomainObjectContainer<*>
                val buildType = buildTypes.findByName(buildTypeName) ?: return false
                return buildType.javaClass.getMethod("isMinifyEnabled").invoke(buildType) as Boolean
            } catch (_: Exception) {
            }
            return false
        }
    }
}
