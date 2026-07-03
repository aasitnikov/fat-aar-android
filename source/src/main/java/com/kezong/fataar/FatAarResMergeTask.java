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
 * Merges the exploded {@code res} folders of the embedded (embed) AARs into a single Android
 * resource source directory.
 *
 * <p>The output directory is wired into the variant via
 * {@link com.android.build.api.variant.SourceDirectories#addGeneratedSourceDirectory}. AGP overrides
 * {@link #getOutputDirectory()} (via convention) with a build-managed location and makes the
 * resource-merging pipeline depend on this task, so the merged folder ends up in the produced fat
 * AAR ({@code res/} + regenerated {@code R.txt}).
 *
 * <p>The embedded AARs each ship their own {@code res/} in the standard layout, so equally-named
 * files collide when flattened into one folder. For {@code values} resources the file name is
 * irrelevant to the resource identity, so we disambiguate them per-library; AGP then merges the
 * contents. For all other resource types the file name IS the resource name, so they are kept as-is.
 *
 * <p>The task is registered for every fat-aar variant (including ones with no embeds, where it just
 * produces an empty folder), so it must be configuration-cache compatible.
 */
public abstract class FatAarResMergeTask extends DefaultTask {

    /**
     * The deterministic task name for a variant.
     */
    static String nameFor(String variantName) {
        String capitalized = variantName.isEmpty()
                ? variantName
                : Character.toUpperCase(variantName.charAt(0)) + variantName.substring(1);
        return "fatAarMergeRes" + capitalized;
    }

    @InputFiles
    @PathSensitive(PathSensitivity.RELATIVE)
    public abstract ConfigurableFileCollection getResDirectories();

    @OutputDirectory
    public abstract DirectoryProperty getOutputDirectory();

    @Inject
    public abstract FileSystemOperations getFileSystemOperations();

    @TaskAction
    public void merge() {
        File outDir = getOutputDirectory().get().getAsFile();
        getFileSystemOperations().delete(spec -> spec.delete(outDir));
        outDir.mkdirs();

        int libIndex = 0;
        for (File resDir : getResDirectories().getFiles()) {
            if (!resDir.isDirectory()) {
                continue;
            }
            final int index = libIndex++;
            getFileSystemOperations().copy(spec -> {
                spec.from(resDir);
                spec.into(outDir);
                spec.setDuplicatesStrategy(DuplicatesStrategy.INCLUDE);
                spec.eachFile(fcd -> {
                    String[] segments = fcd.getRelativePath().getSegments();
                    if (segments.length > 0 && segments[0].startsWith("values")) {
                        fcd.setRelativePath(fcd.getRelativePath()
                                .replaceLastName("fataar_" + index + "_" + fcd.getName()));
                    }
                });
            });
        }
    }
}
