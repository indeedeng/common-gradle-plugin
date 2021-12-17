package com.indeed.ossgradle.internal;

import org.gradle.api.Plugin;
import org.gradle.api.Project;

public class ConfigureReposPlugin implements Plugin<Project> {
    @Override
    public void apply(final Project project) {
        project.getRepositories().mavenCentral();
    }
}
