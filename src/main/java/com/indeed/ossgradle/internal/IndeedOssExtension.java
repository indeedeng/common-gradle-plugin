package com.indeed.ossgradle.internal;

import org.gradle.api.Project;

public class IndeedOssExtension {
    private final Project project;

    public IndeedOssExtension(final Project project) {
        this.project = project;
    }

    public void activateFeature(final String id) {
        project.getPlugins().apply(IndeedOssFeaturePlugin.class).activateFeature(id);
    }
}
