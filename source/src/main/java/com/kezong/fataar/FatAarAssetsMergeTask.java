package com.kezong.fataar;

import org.gradle.api.DefaultTask;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.DuplicatesStrategy;
import org.gradle.api.file.FileSystemOperations;
import org.gradle.api.tasks.InputFiles;
import org.gradle.api.tasks.OutputDirectory;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;

import javax.inject.Inject;
import java.io.File;

/**
 * Merges the exploded {@code assets} folders of the embedded (embed) AARs into a single Android
 * assets source directory.
 *
 * <p>The output directory is wired into the variant via
 * {@link com.android.build.api.variant.SourceDirectories#addGeneratedSourceDirectory}. AGP overrides
 * {@link #getOutputDirectory()} (via convention) with a build-managed location and makes the
 * assets-merging pipeline depend on this task, so the merged folder ends up in the produced fat AAR.
 *
 * <p>Unlike {@code res}, an asset's identity IS its relative path, so files are copied as-is with no
 * per-library renaming. Each embedded AAR is copied in declared order, so a path present in more than
 * one library resolves to whichever library is processed last — the same "last one wins" outcome the
 * legacy {@code SourceDirectorySet.srcDir(...)}-based merge produced.
 *
 * <p>The task is registered for every fat-aar variant (including ones with no embeds, where it just
 * produces an empty folder), so it must be configuration-cache compatible.
 */
public abstract class FatAarAssetsMergeTask extends DefaultTask {

    /**
     * The deterministic task name for a variant.
     */
    static String nameFor(String variantName) {
        String capitalized = variantName.isEmpty()
                ? variantName
                : Character.toUpperCase(variantName.charAt(0)) + variantName.substring(1);
        return "fatAarMergeAssets" + capitalized;
    }

    @InputFiles
    @PathSensitive(PathSensitivity.RELATIVE)
    public abstract ConfigurableFileCollection getAssetDirectories();

    @OutputDirectory
    public abstract DirectoryProperty getOutputDirectory();

    @Inject
    public abstract FileSystemOperations getFileSystemOperations();

    @TaskAction
    public void merge() {
        File outDir = getOutputDirectory().get().getAsFile();
        getFileSystemOperations().delete(spec -> spec.delete(outDir));
        outDir.mkdirs();

        for (File assetsDir : getAssetDirectories().getFiles()) {
            if (!assetsDir.isDirectory()) {
                continue;
            }
            getFileSystemOperations().copy(spec -> {
                spec.from(assetsDir);
                spec.into(outDir);
                spec.setDuplicatesStrategy(DuplicatesStrategy.INCLUDE);
            });
        }
    }
}
