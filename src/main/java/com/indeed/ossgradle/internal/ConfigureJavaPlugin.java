package com.indeed.ossgradle.internal;

import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.tasks.compile.JavaCompile;

public class ConfigureJavaPlugin implements Plugin<Project> {
    @Override
    public void apply(final Project project) {
        project.getTasks()
                .withType(JavaCompile.class)
                .configureEach(
                        task -> {
                            task.setSourceCompatibility("1.8");
                            task.setTargetCompatibility("1.8");
                            task.getOptions().setEncoding("UTF-8");
                        });
    }
}
