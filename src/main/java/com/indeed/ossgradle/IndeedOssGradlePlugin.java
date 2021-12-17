package com.indeed.ossgradle;

import com.indeed.ossgradle.internal.ConfigureJavaPlugin;
import com.indeed.ossgradle.internal.ConfigureReposPlugin;
import com.indeed.ossgradle.internal.IndeedOssExtension;
import org.gradle.api.Plugin;
import org.gradle.api.Project;

public class IndeedOssGradlePlugin implements Plugin<Project> {
    @Override
    public void apply(final Project project) {
        if (project != project.getRootProject()) {
            throw new IllegalStateException("com.indeed.oss can only be applied to the root project");
        }

        project.allprojects(p -> {
            p.getPlugins().apply(ConfigureJavaPlugin.class);
            p.getPlugins().apply(ConfigureReposPlugin.class);
            p.getExtensions().create("indeedOss", IndeedOssExtension.class, p);
        });
    }
}
