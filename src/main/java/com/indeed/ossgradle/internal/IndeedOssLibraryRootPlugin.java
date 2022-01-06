package com.indeed.ossgradle.internal;

import com.google.common.base.Joiner;
import com.google.common.base.Splitter;
import com.google.common.base.Suppliers;
import org.apache.commons.lang3.StringUtils;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.internal.artifacts.ivyservice.ivyresolve.strategy.DefaultVersionComparator;
import org.gradle.api.internal.artifacts.ivyservice.ivyresolve.strategy.Version;
import org.gradle.api.internal.artifacts.ivyservice.ivyresolve.strategy.VersionParser;

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

public class IndeedOssLibraryRootPlugin implements Plugin<Project> {
    private static final String PUBLOCAL_VERSION_PREFIX = "0.local.";
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
                            rootProject.getLogger().lifecycle("Calculating next version ...");
                            final String version = calculateVersion(rootProject, local);
                            rootProject.getLogger().lifecycle(version);
                            return version;
                        });
        httpUrlSupplier = Suppliers.memoize(() -> GitUtil.getHttpUrl(rootProject));
    }

    private String calculateVersion(final Project project, final boolean local) {
        if (local) {
            return PUBLOCAL_VERSION_PREFIX + localVersionFormatter.format(Instant.now());
        }

        final Set<String> tags = new HashSet<>(GitUtil.getTags(project));

        final String latestVersion =
                tags.stream()
                        .filter(tag -> tag.startsWith(TAG_PREFIX))
                        .map(tag -> tag.substring(TAG_PREFIX.length()))
                        .filter(v -> !v.contains("-dev"))
                        .map(v -> StringUtils.substringBefore(v, "-"))
                        .max(IndeedOssLibraryRootPlugin::compareVersion)
                        .orElse(null);
        project.getLogger().lifecycle("Latest existing version: " + latestVersion);

        final String defaultBranch = GitUtil.getDefaultBranch(project);
        final String currentBranch = GitUtil.getCurrentBranch(project);
        project.getLogger().lifecycle("Default branch: " + defaultBranch);
        project.getLogger().lifecycle("Current branch: " + currentBranch);
        final String suffix;
        final boolean isDev;
        final String shortHash = GitUtil.getShortHash(project);
        if (!StringUtils.equals(defaultBranch, currentBranch)) {
            String shortBranch = currentBranch;
            shortBranch = StringUtils.replace(shortBranch, "jira/", "");
            shortBranch = StringUtils.replace(shortBranch, "/", "-");
            shortBranch = StringUtils.replace(shortBranch, ".", "-");
            suffix = "-dev-" + shortBranch + "-" + shortHash;
            isDev = true;
        } else {
            suffix = "-" + shortHash;
            isDev = false;
        }

        String newVersion;
        if (latestVersion == null) {
            newVersion = "1.0.0";
        } else {
            final List<String> split = new ArrayList<>(Splitter.on('.').splitToList(latestVersion));
            int patchVersion = Integer.parseInt(split.get(split.size() - 1));
            if (!isDev) {
                patchVersion++;
            }
            split.set(split.size() - 1, String.valueOf(patchVersion));
            newVersion = Joiner.on('.').join(split);
        }

        if (!StringUtils.isEmpty(suffix)) {
            for (int i = 1; ; i++) {
                String test = newVersion + suffix;
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
}
