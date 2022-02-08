package com.indeed.ossgradle.internal;

import com.diffplug.gradle.spotless.SpotlessExtension;
import com.diffplug.gradle.spotless.SpotlessPlugin;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.plugins.JavaPlugin;
import org.gradle.api.tasks.compile.AbstractCompile;

public class IndeedSpotlessPlugin implements Plugin<Project> {
    @Override
    public void apply(final Project project) {
        project.getPlugins().apply(SpotlessPlugin.class);
        final SpotlessExtension ext = project.getExtensions().getByType(SpotlessExtension.class);
        ext.setEnforceCheck(false);

        project.getPlugins().withType(JavaPlugin.class, p -> applySpotlessJava(project, ext));

        project.getPlugins().withId("kotlin", p -> applySpotlessKotlin(project, ext));
        project.getPlugins().withId("kotlin-android", p -> applySpotlessKotlin(project, ext));
    }

    public void applySpotlessJava(final Project project, final SpotlessExtension ext) {
        ext.java(
                java -> {
                    java.toggleOffOn();
                    java.targetExclude(
                            project.fileTree(
                                    project.getBuildDir(), tree -> tree.include("**/*.java")));
                    java.removeUnusedImports();
                    java.trimTrailingWhitespace();
                    java.endWithNewline();
                    java.googleJavaFormat("1.7").aosp();
                    java.importOrder("", "javax", "java", "\\#");
                    java.replaceRegex(
                            "Remove extra line between javax and java",
                            "^(import javax\\..*)\n\n(import java\\..*)",
                            "$1\n$2");
                });
        if (IndeedOssLibraryRootPlugin.getCiWorkspace(project) == null) {
            project.getTasks()
                    .withType(AbstractCompile.class)
                    .configureEach(
                            compile -> {
                                compile.finalizedBy("spotlessApply");
                            });
        }
    }

    public void applySpotlessKotlin(final Project project, final SpotlessExtension ext) {
        ext.kotlin(
                kotlin -> {
                    kotlin.toggleOffOn();
                    kotlin.targetExclude(
                            project.fileTree(
                                    project.getBuildDir(), tree -> tree.include("**/*.kt")));
                    kotlin.target("**/*.kt", "**/*.kts");
                    kotlin.trimTrailingWhitespace();
                    kotlin.endWithNewline();
                    kotlin.ktlint();
                });

        if (IndeedOssLibraryRootPlugin.getCiWorkspace(project) == null) {
            project.getTasks()
                    .configureEach(
                            task -> {
                                if (task.getName().equals("kotlinCompile")
                                        || (task.getName().startsWith("compile")
                                                && task.getName().endsWith("Kotlin"))) {
                                    task.finalizedBy("spotlessApply");
                                }
                            });
        }
    }
}
