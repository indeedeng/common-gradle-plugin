package com.indeed.ossgradle;

import com.indeed.ossgradle.internal.IndeedOssExtension;
import com.indeed.ossgradle.internal.IndeedOssUtil;
import org.gradle.api.Plugin;
import org.gradle.api.Project;

public class IndeedOssGradlePlugin implements Plugin<Project> {
    @Override
    public void apply(final Project rootProject) {
        IndeedOssUtil.assertRootProject(rootProject);
        rootProject.allprojects(
                p -> p.getExtensions().create("indeedOss", IndeedOssExtension.class, p));
    }
}
