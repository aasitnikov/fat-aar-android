package com.kezong.fataar

open class FatAarExtension {
    companion object {
        /**
         * Used in RClassAsmTransformerFactory by reflection, don't change the name.
         */
        const val NAME = "fataar"
    }

    /**
     * If transitive is true, local jar module and remote library's dependencies will be embed.
     * (local aar module does not support)
     * If transitive is false, just embed first level dependency
     * Default value is false
     * @since 1.3.0
     */
    var transitive: Boolean = false
}
