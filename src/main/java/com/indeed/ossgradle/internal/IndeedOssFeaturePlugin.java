package com.indeed.ossgradle.internal;

import com.google.common.collect.ImmutableMap;
import org.gradle.api.Plugin;
import org.gradle.api.Project;

import java.util.Map;

/**
 * This allows client projects to apply com.indeed.oss "feature" sub-plugins by name without
 * us needing to wait for gradle plugin portal to approve the new names.
 */
public class IndeedOssFeaturePlugin implements Plugin<Project> {
    private static final Map<String, Class<? extends Plugin<Project>>> featureMapping = ImmutableMap.<String, Class<? extends Plugin<Project>>>builder()
            .put("gradle-plugin", IndeedOssGradlePluginPlugin.class)
            .put("library", IndeedOssLibraryPlugin.class)
            .build();

    private Project project;

    @Override
    public void apply(final Project project) {
        this.project = project;
    }

    public void activateFeature(final String id) {
        if (featureMapping.containsKey(id)) {
            project.getPlugins().apply(featureMapping.get(id));
            return;
        }
        throw new IllegalArgumentException("Unknown indeed oss feature: " + id);
    }
}
