package com.indeed.ossgradle;

import com.indeed.ossgradle.internal.ConfigureJavaPlugin;
import com.indeed.ossgradle.internal.IndeedOssPublishPlugin;
import org.gradle.api.Plugin;
import org.gradle.api.Project;

public class IndeedOssGradlePlugin implements Plugin<Project> {
    @Override
    public void apply(final Project project) {
        if (project != project.getRootProject()) {
            throw new IllegalStateException("com.indeed.oss can only be applied to the root project");
        }

        project.getPlugins().apply(IndeedOssPublishPlugin.class);

        project.allprojects(p -> {
            p.getPlugins().apply(ConfigureJavaPlugin.class);
        });
    }
}
