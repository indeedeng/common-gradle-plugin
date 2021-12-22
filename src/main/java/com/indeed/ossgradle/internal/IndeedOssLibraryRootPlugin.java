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
    private static final String PUBLOCAL_VERSION_PREFIX = "0.local.";
    private static final DateTimeFormatter localVersionFormatter =
            DateTimeFormatter.ofPattern("yyyyMMddHHmmss").withZone(ZoneOffset.UTC);
    private static final String TAG_PREFIX = "published/";
    private static final Comparator<Version> VERSION_COMPARATOR =
            new DefaultVersionComparator().asVersionComparator();
    private static final VersionParser VERSION_PARSER = new VersionParser();

    private Supplier<String> rcSuffixSupplier;
    private Supplier<String> versionSupplier;
    private Supplier<Boolean> isLocalPublishSupplier;
    private Supplier<Path> ciWorkspaceSupplier;
    private Supplier<String> httpUrlSupplier;
    private TaskProvider<Task> pushTagTask;

    public void apply(final Project rootProject) {
        if (rootProject != rootProject.getRootProject()) {
            throw new IllegalStateException("This can only be applied to the root project");
        }

        ciWorkspaceSupplier = Suppliers.memoize(() -> getCiWorkspace(rootProject));
        isLocalPublishSupplier = Suppliers.memoize(() -> ciWorkspaceSupplier.get() == null);
        rcSuffixSupplier = Suppliers.memoize(() -> calculateRcSuffix(rootProject));
        versionSupplier =
                Suppliers.memoize(
                        () -> {
                            final boolean local = getIsLocalPublish();
                            rootProject.getLogger().lifecycle("Calculating next version ...");
                            final String version = calculateVersion(rootProject, local);
                            rootProject.getLogger().lifecycle(version);
                            return version;
                        });
        httpUrlSupplier = Suppliers.memoize(() -> GitUtil.getHttpUrl(rootProject));
        pushTagTask =
                rootProject
                        .getTasks()
                        .register(
                                "pushPublishTag",
                                task -> {
                                    task.doFirst(
                                            t -> {
                                                pushPublishTag(rootProject);
                                            });
                                });
    }

    private void pushPublishTag(final Project project) {
        final boolean local = getIsLocalPublish();
        if (local) {
            return;
        }
        final String rcSuffix = getRcSuffix();
        if (!StringUtils.isEmpty(rcSuffix)) {
            return;
        }
        final String version = versionSupplier.get();
        GitUtil.commitGitTag(project, TAG_PREFIX + version, "Publishing " + version);
    }

    private String calculateRcSuffix(final Project project) {
        final String defaultBranch = GitUtil.getDefaultBranch(project);
        final String currentBranch = GitUtil.getCurrentBranch(project);
        project.getLogger().lifecycle("Default branch: " + defaultBranch);
        project.getLogger().lifecycle("Current branch: " + currentBranch);
        final String rcSuffix;
        if (!StringUtils.equals(defaultBranch, currentBranch)) {
            final String shortHash = GitUtil.getShortHash(project);
            String shortBranch = currentBranch;
            shortBranch = StringUtils.replace(shortBranch, "jira/", "");
            shortBranch = StringUtils.replace(shortBranch, "/", "-");
            shortBranch = StringUtils.replace(shortBranch, ".", "-");
            rcSuffix = "-dev-" + shortBranch + "-" + shortHash;
        } else {
            rcSuffix = "";
        }
        return rcSuffix;
    }

    private String calculateVersion(final Project project, final boolean local) {
        if (local) {
            return PUBLOCAL_VERSION_PREFIX + localVersionFormatter.format(Instant.now());
        }

        final Set<String> tags = new HashSet<>(GitUtil.getTags(project));

        final String latestVersion =
                tags.stream()
                        .filter((tag) -> tag.matches(Pattern.quote(TAG_PREFIX) + "(.*\\.[0-9]+)?"))
                        .filter((tag) -> !tag.contains("-dev"))
                        .map((tag) -> tag.substring(TAG_PREFIX.length()))
                        .max(IndeedOssLibraryRootPlugin::compareVersion)
                        .orElse(null);
        project.getLogger().lifecycle("Latest existing version: " + latestVersion);

        final String rcSuffix = getRcSuffix();

        String newVersion;
        if (latestVersion == null) {
            newVersion = "1.0.0";
        } else {
            final List<String> split = new ArrayList<>(Splitter.on('.').splitToList(latestVersion));
            int patchVersion = Integer.parseInt(split.get(split.size() - 1));
            if (StringUtils.isEmpty(rcSuffix)) {
                patchVersion++;
            }
            split.set(split.size() - 1, String.valueOf(patchVersion));
            newVersion = Joiner.on('.').join(split);
        }

        if (!StringUtils.isEmpty(rcSuffix)) {
            for (int i = 1; ; i++) {
                String test = newVersion + rcSuffix;
                if (i != 1) {
                    test += "-" + i;
                }
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

    public String getRcSuffix() {
        return rcSuffixSupplier.get();
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
        return pushTagTask;
    }
}
