package com.indeed.ossgradle.internal;

import org.gradle.api.Project;

public class IndeedOssUtil {
    /**
     * By default, project.afterEvaluate is executed even if there was an exception thrown during
     * configuration. This causes all sorts of havok, primarily in com.indeed.publish projects where
     * various plugins will attempt to read the library's name, and will throw a
     * "java.lang.RuntimeException: indeedPublish.name must be set", because the build.gradle failed
     * before the project was able to set the name. Pretty much everything should use this helper
     * instead, which will skip running the callback if the project is failing out.
     */
    public static void afterEvaluate(final Project project, final Runnable fn) {
        project.afterEvaluate(
                p -> {
                    if (p.getState().getFailure() != null) {
                        return;
                    }
                    fn.run();
                });
    }

    public static void assertRootProject(final Project project) {
        if (project != project.getRootProject()) {
            throw new IllegalStateException("This plugin can only be applied to the root project");
        }
    }
}
