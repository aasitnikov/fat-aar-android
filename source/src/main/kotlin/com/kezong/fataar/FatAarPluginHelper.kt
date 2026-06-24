package com.kezong.fataar

import com.android.build.api.instrumentation.AsmClassVisitorFactory
import com.android.build.api.instrumentation.ClassContext
import com.android.build.api.instrumentation.ClassData
import com.android.build.api.instrumentation.FramesComputationMode
import com.android.build.api.instrumentation.InstrumentationParameters
import com.android.build.api.instrumentation.InstrumentationScope
import com.android.build.api.variant.AndroidComponentsExtension
import com.android.build.api.variant.Variant
import org.gradle.api.Project
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Optional
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.commons.ClassRemapper
import org.objectweb.asm.commons.Remapper
import java.util.stream.Collectors

object FatAarPluginHelper {

    fun registerAsmTransformation(
        project: Project,
        variantPackagesProperty: MapProperty<String, List<AndroidArchiveLibrary>>
    ) {
        val components = project.extensions.getByType(AndroidComponentsExtension::class.java)
        components.onVariants(components.selector().all()) { variant: Variant ->
            variant.instrumentation.transformClassesWith(
                RClassAsmTransformerFactory::class.java,
                InstrumentationScope.PROJECT
            ) { params ->
                params.namespace.set(variant.namespace)
                params.libraryNamespaces.set(
                    variantPackagesProperty.getting(variant.name)
                        .map { list -> list.stream().map { it.getPackageName() }.collect(Collectors.toList()) }
                )
            }
            variant.instrumentation.setAsmFramesComputationMode(FramesComputationMode.COPY_FRAMES)
        }
    }

    interface RClassParams : InstrumentationParameters {
        @get:Input
        val namespace: Property<String>

        @get:Input
        @get:Optional
        val libraryNamespaces: ListProperty<String>
    }

    abstract class RClassAsmTransformerFactory : AsmClassVisitorFactory<RClassParams> {

        override fun createClassVisitor(
            classContext: ClassContext,
            nextClassVisitor: ClassVisitor
        ): ClassVisitor {
            val params = parameters.get()
            val namespace = params.namespace.get()
            val libraryNamespaces = params.libraryNamespaces.orElse(emptyList()).get()

            if (libraryNamespaces.isEmpty()) {
                return nextClassVisitor
            }

            val targetRClass = namespace.replace(".", "/") + "/R"
            val targetRSubclass = namespace.replace(".", "/") + "/R$"

            val libraryRClasses = libraryNamespaces.map { it.replace(".", "/") + "/R" }.toSet()
            val libraryRSubclasses = libraryNamespaces.map { it.replace(".", "/") + "/R$" }

            val remapper = object : Remapper() {
                override fun map(internalName: String?): String? {
                    if (internalName == null) return null
                    if (libraryRClasses.contains(internalName)) return targetRClass
                    for (libraryRSubclass in libraryRSubclasses) {
                        if (internalName.startsWith(libraryRSubclass)) {
                            return internalName.replaceFirst(libraryRSubclass, targetRSubclass)
                        }
                    }
                    return super.map(internalName)
                }
            }
            return ClassRemapper(nextClassVisitor, remapper)
        }

        override fun isInstrumentable(classData: ClassData): Boolean = true
    }
}
