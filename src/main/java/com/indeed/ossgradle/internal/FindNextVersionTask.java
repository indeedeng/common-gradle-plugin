package com.indeed.ossgradle.internal;

import com.google.common.collect.ImmutableList;
import org.gradle.api.DefaultTask;
import org.gradle.api.internal.artifacts.DefaultModuleIdentifier;
import org.gradle.api.tasks.TaskAction;
import org.gradle.api.tasks.options.Option;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

public class FindNextVersionTask extends DefaultTask {
    private String group;
    private String name;
    private boolean isDev = false;

    @Option(option = "group", description = "")
    public void setGroupOption(final String group) {
        this.group = group;
    }

    @Option(option = "name", description = "")
    public void setNameOption(final String name) {
        this.name = name;
    }

    @Option(option = "dev", description = "")
    public void setDevOption(final boolean isDev) {
        this.isDev = isDev;
    }

    @TaskAction
    public void run() throws IOException {
        final String nextVersion =
                IndeedOssLibraryRootPlugin.calculateNextVersionFromIds(
                        getProject(),
                        ImmutableList.of(DefaultModuleIdentifier.newId(group, name)),
                        isDev);
        getProject().getLogger().lifecycle(nextVersion);
        Files.write(
                getProject().file("nextversion.txt").toPath(),
                nextVersion.getBytes(StandardCharsets.UTF_8));
    }
}
