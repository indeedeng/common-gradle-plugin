package com.indeed.ossgradle.internal;

import org.gradle.api.Plugin;
import org.gradle.api.Project;

public class FindNextVersionPlugin implements Plugin<Project> {
    @Override
    public void apply(final Project project) {
        IndeedOssUtil.assertRootProject(project);
        project.getTasks().register("findNextVersion", FindNextVersionTask.class);
    }
}
