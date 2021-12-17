package com.indeed.ossgradle.internal;

import com.google.common.base.Joiner;
import com.google.common.base.Splitter;
import com.google.common.base.Suppliers;
import org.apache.commons.lang3.StringUtils;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.Task;
import org.gradle.api.internal.artifacts.ivyservice.ivyresolve.strategy.DefaultVersionComparator;
import org.gradle.api.internal.artifacts.ivyservice.ivyresolve.strategy.Version;
import org.gradle.api.internal.artifacts.ivyservice.ivyresolve.strategy.VersionParser;
import org.gradle.api.tasks.TaskProvider;

import javax.annotation.Nullable;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;
import java.util.regex.Pattern;

public class IndeedOssLibraryRootPlugin implements Plugin<Project> {
    public static final String PUBLOCAL_VERSION_PREFIX = "0.local.";
    private static final DateTimeFormatter localVersionFormatter =
            DateTimeFormatter.ofPattern("yyyyMMddHHmmss").withZone(ZoneOffset.UTC);
    private static final String TAG_PREFIX = "published/";
    private static final Comparator<Version> VERSION_COMPARATOR =
            new DefaultVersionComparator().asVersionComparator();
    private static final VersionParser VERSION_PARSER = new VersionParser();

    private Supplier<String> versionSupplier;
    private Supplier<Boolean> isLocalPublishSupplier;
    private Supplier<Path> ciWorkspaceSupplier;
    private Supplier<String> httpUrlSupplier;
    private Supplier<TaskProvider<Task>> pushTagTaskSupplier;

    public void apply(final Project rootProject) {
        if (rootProject != rootProject.getRootProject()) {
            throw new IllegalStateException("This can only be applied to the root project");
        }

        ciWorkspaceSupplier = Suppliers.memoize(() -> getCiWorkspace(rootProject));
        isLocalPublishSupplier = Suppliers.memoize(() -> ciWorkspaceSupplier.get() == null);

        versionSupplier =
                Suppliers.memoize(
                        () -> {
                            final boolean local = getIsLocalPublish();
                            final boolean isRc =
                                    !local
                                            && !StringUtils.equals(
                                                    GitUtil.getDefaultBranch(rootProject),
                                                    GitUtil.getCurrentBranch(rootProject));
                            rootProject.getLogger().lifecycle("Calculating next version ...");
                            if (!local && !isRc) {
                                rootProject
                                        .getLogger()
                                        .warn(
                                                "Detected default branch on CI - we're in full publish go mode");
                            }
                            final String version = calculateVersion(rootProject, local, isRc);
                            rootProject.getLogger().lifecycle(version);
                            return version;
                        });
        httpUrlSupplier = Suppliers.memoize(() -> GitUtil.getHttpUrl(rootProject));
        pushTagTaskSupplier =
                Suppliers.memoize(
                        () -> {
                            if (getIsLocalPublish()) {
                                return null;
                            }
                            return rootProject
                                    .getTasks()
                                    .register(
                                            "pushPublishTag",
                                            task -> {
                                                task.doFirst(
                                                        t -> {
                                                            final boolean local =
                                                                    getIsLocalPublish();
                                                            if (local) {
                                                                return;
                                                            }
                                                            final String version =
                                                                    versionSupplier.get();
                                                            GitUtil.commitGitTag(
                                                                    rootProject,
                                                                    TAG_PREFIX + version,
                                                                    "Publishing " + version);
                                                        });
                                            });
                        });
    }

    private String calculateVersion(
            final Project project, final boolean local, final boolean isRc) {
        if (local) {
            return PUBLOCAL_VERSION_PREFIX + localVersionFormatter.format(Instant.now());
        }

        final Set<String> tags = new HashSet<>(GitUtil.getTags(project));

        final String latestVersion =
                tags.stream()
                        .filter((tag) -> tag.matches(Pattern.quote(TAG_PREFIX) + "(.*\\.[0-9]+)?"))
                        .filter((tag) -> !tag.contains("-rc"))
                        .map((tag) -> tag.substring(TAG_PREFIX.length()))
                        .max(IndeedOssLibraryRootPlugin::compareVersion)
                        .orElse(null);
        project.getLogger().info("Latest version: " + latestVersion);

        String newVersion;
        if (latestVersion == null) {
            newVersion = "1.0.0";
        } else {
            final List<String> split = new ArrayList<>(Splitter.on('.').splitToList(latestVersion));
            int patchVersion = Integer.parseInt(split.get(split.size() - 1)) + 1;
            split.set(split.size() - 1, String.valueOf(patchVersion));
            newVersion = Joiner.on('.').join(split);
        }

        if (isRc) {
            for (int i = 1; ; i++) {
                final String test = newVersion + "-rc" + i;
                if (!tags.contains(TAG_PREFIX + test)) {
                    newVersion = test;
                    break;
                }
            }
        }

        return newVersion;
    }

    private static int compareVersion(final String a, final String b) {
        return VERSION_COMPARATOR.compare(VERSION_PARSER.transform(a), VERSION_PARSER.transform(b));
    }

    @Nullable
    public static Path getCiWorkspace(final Project project) {
        String workspaceDir = System.getenv("WORKSPACE");
        if (workspaceDir == null) {
            workspaceDir = System.getenv("CI_PROJECT_DIR");
        }
        if (workspaceDir == null) {
            workspaceDir = System.getenv("GITHUB_WORKSPACE");
        }
        if (workspaceDir == null) {
            return null;
        }
        return Paths.get(workspaceDir);
    }

    public String getVersion() {
        return versionSupplier.get();
    }

    public boolean getIsLocalPublish() {
        return isLocalPublishSupplier.get();
    }

    @Nullable
    public Path getCiWorkspace() {
        return ciWorkspaceSupplier.get();
    }

    public String getHttpUrl() {
        return httpUrlSupplier.get();
    }

    @Nullable
    public TaskProvider<Task> getPushTagTask() {
        return pushTagTaskSupplier.get();
    }
}
