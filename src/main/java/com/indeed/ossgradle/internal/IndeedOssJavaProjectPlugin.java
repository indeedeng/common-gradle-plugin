package com.indeed.ossgradle.internal;

import org.gradle.api.Plugin;
import org.gradle.api.Project;

import java.util.concurrent.TimeUnit;

/** Should be applied to the root project of "common java" projects. */
public class IndeedOssJavaProjectPlugin implements Plugin<Project> {
    @Override
    public void apply(final Project project) {
        project.getConfigurations()
                .configureEach(
                        conf -> {
                            conf.getResolutionStrategy()
                                    .cacheDynamicVersionsFor(1, TimeUnit.MINUTES);
                        });
        project.getPlugins().apply(ConfigureJavaPlugin.class);
        project.getPlugins().apply(ConfigureReposPlugin.class);
        project.getPlugins().apply(IndeedSpotlessPlugin.class);
    }
}
