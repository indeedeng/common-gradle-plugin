package com.indeed.ossgradle;

import com.indeed.ossgradle.internal.ConfigureJavaPlugin;
import com.indeed.ossgradle.internal.ConfigureReposPlugin;
import com.indeed.ossgradle.internal.FindNextVersionTask;
import com.indeed.ossgradle.internal.IndeedOssExtension;
import com.indeed.ossgradle.internal.IndeedSpotlessPlugin;
import org.gradle.api.Plugin;
import org.gradle.api.Project;

import java.util.concurrent.TimeUnit;

public class IndeedOssGradlePlugin implements Plugin<Project> {
    @Override
    public void apply(final Project project) {
        if (project != project.getRootProject()) {
            throw new IllegalStateException(
                    "com.indeed.oss can only be applied to the root project");
        }

        project.getTasks().register("findNextVersion", FindNextVersionTask.class);

        project.allprojects(
                p -> {
                    p.getConfigurations().configureEach(conf -> {
                        conf.getResolutionStrategy().cacheDynamicVersionsFor(1, TimeUnit.MINUTES);
                    });
                    p.getPlugins().apply(ConfigureJavaPlugin.class);
                    p.getPlugins().apply(ConfigureReposPlugin.class);
                    p.getPlugins().apply(IndeedSpotlessPlugin.class);
                    p.getExtensions().create("indeedOss", IndeedOssExtension.class, p);
                });
    }
}
