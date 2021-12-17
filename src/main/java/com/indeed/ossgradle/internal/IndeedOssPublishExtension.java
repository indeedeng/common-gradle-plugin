package com.indeed.ossgradle.internal;

import org.gradle.api.Project;
import org.gradle.api.plugins.ExtraPropertiesExtension;
import org.gradle.api.provider.Property;

public class IndeedOssPublishExtension {
    private final Property<String> group;
    private final Property<String> name;

    public IndeedOssPublishExtension(final Project project) {
        group = project.getObjects().property(String.class);
        group.finalizeValueOnRead();
        group.set("com.indeed");

        name = project.getObjects().property(String.class);
        name.finalizeValueOnRead();
        name.set(project.provider(() -> {
            final ExtraPropertiesExtension ext = project.getExtensions().getExtraProperties();
            if (ext.has("indeed.publish.name")) {
                return (String)ext.get("indeed.publish.name");
            }
            throw new IllegalArgumentException("indeedPublish.name must be set");
        }));
    }

    public Property<String> getGroup() {
        return group;
    }

    public Property<String> getName() {
        return name;
    }
}
