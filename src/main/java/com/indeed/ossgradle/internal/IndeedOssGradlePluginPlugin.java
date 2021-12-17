package com.indeed.ossgradle.internal;

import com.gradle.publish.PublishPlugin;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.plugin.devel.plugins.JavaGradlePluginPlugin;

/** Applied when the current project is an indeed gradle plugin */
public class IndeedOssGradlePluginPlugin implements Plugin<Project> {
    @Override
    public void apply(final Project project) {
        project.getPlugins().apply(JavaGradlePluginPlugin.class);
        project.getPlugins().apply(PublishPlugin.class);
        project.getPlugins().apply(IndeedOssLibraryPlugin.class);
    }
}
