package com.indeed.ossgradle.internal;

import com.google.common.base.Joiner;
import com.google.common.base.Splitter;
import com.google.common.base.Suppliers;
import org.apache.commons.lang3.StringUtils;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.artifacts.ModuleIdentifier;
import org.gradle.api.artifacts.ResolvedDependency;
import org.gradle.api.artifacts.repositories.ArtifactRepository;
import org.gradle.api.internal.artifacts.DefaultModuleIdentifier;
import org.gradle.api.internal.artifacts.ivyservice.ivyresolve.strategy.DefaultVersionComparator;
import org.gradle.api.internal.artifacts.ivyservice.ivyresolve.strategy.Version;
import org.gradle.api.internal.artifacts.ivyservice.ivyresolve.strategy.VersionParser;
import org.gradle.api.invocation.Gradle;

import javax.annotation.Nullable;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.stream.Collectors;

public class IndeedOssLibraryRootPlugin implements Plugin<Project> {
    private static final String PUBLOCAL_VERSION_PREFIX = "0.local.";
    private static final DateTimeFormatter localVersionFormatter =
            DateTimeFormatter.ofPattern("yyyyMMddHHmmss").withZone(ZoneOffset.UTC);
    private static final Comparator<Version> VERSION_COMPARATOR =
            new DefaultVersionComparator().asVersionComparator();
    private static final VersionParser VERSION_PARSER = new VersionParser();

    private Supplier<String> versionSupplier;
    private Supplier<Boolean> isLocalPublishSupplier;
    private Supplier<Path> ciWorkspaceSupplier;
    private Supplier<String> httpUrlSupplier;

    public void apply(final Project rootProject) {
        IndeedOssUtil.assertRootProject(rootProject);

        rootProject
                .getGradle()
                .projectsEvaluated(
                        g -> {
                            for (final Project project : rootProject.getAllprojects()) {
                                final IndeedOssLibraryPlugin p =
                                        project.getPlugins()
                                                .findPlugin(IndeedOssLibraryPlugin.class);
                                if (p != null) {
                                    p.onVersionReady(getVersion());
                                }
                            }
                        });

        ciWorkspaceSupplier = Suppliers.memoize(() -> getCiWorkspace(rootProject));
        isLocalPublishSupplier = Suppliers.memoize(() -> ciWorkspaceSupplier.get() == null);
        versionSupplier =
                Suppliers.memoize(
                        () -> {
                            final boolean local = getIsLocalPublish();
                            if (rootProject.getGradle().getStartParameter().getTaskRequests()
                                    .stream()
                                    .noneMatch(request -> request.getArgs().contains("publish"))) {
                                return null;
                            }
                            rootProject
                                    .getLogger()
                                    .lifecycle("Calculating version to use for publish ...");
                            final String version = calculateNextVersion(rootProject, local);
                            rootProject.getLogger().lifecycle("Now using version: " + version);
                            return version;
                        });
        httpUrlSupplier = Suppliers.memoize(() -> GitUtil.getHttpUrl(rootProject));
    }

    private static String calculateNextVersion(final Project project, final boolean local) {
        if (local) {
            return PUBLOCAL_VERSION_PREFIX + localVersionFormatter.format(Instant.now());
        }

        final String defaultBranch = GitUtil.getDefaultBranch(project);
        final String currentBranch = GitUtil.getCurrentBranch(project);
        project.getLogger().lifecycle("Default branch: " + defaultBranch);
        project.getLogger().lifecycle("Current branch: " + currentBranch);
        final String suffix;
        final boolean isDev;
        final String shortHash = GitUtil.getShortHash(project);
        if (!StringUtils.equals(defaultBranch, currentBranch)) {
            project.getLogger()
                    .lifecycle("We are not on the default branch, so this is a dev publish");
            String shortBranch = currentBranch;
            shortBranch = StringUtils.replace(shortBranch, "jira/", "");
            shortBranch = StringUtils.replace(shortBranch, "/", "-");
            shortBranch = StringUtils.replace(shortBranch, ".", "-");
            suffix = "-dev-" + shortBranch + "-" + shortHash;
            isDev = true;
        } else {
            project.getLogger().lifecycle("We are on the default branch, so this is a release");
            suffix = "-" + shortHash;
            isDev = false;
        }

        final String nextVersion = calculateNextVersionFromBuild(project.getGradle(), isDev);
        return nextVersion + suffix;
    }

    private static String calculateNextVersionFromBuild(final Gradle gradle, final boolean isDev) {
        final Collection<ModuleIdentifier> ids =
                gradle.getRootProject().getAllprojects().stream()
                        .map(
                                project ->
                                        project.getExtensions()
                                                .findByType(IndeedOssLibraryExtension.class))
                        .filter(ext -> ext != null)
                        .map(
                                ext ->
                                        DefaultModuleIdentifier.newId(
                                                ext.getGroup().get(), ext.getName().get()))
                        .collect(Collectors.toSet());
        return calculateNextVersionFromIds(gradle.getRootProject(), ids, isDev);
    }

    public static String calculateNextVersionFromIds(
            final Project project, final Collection<ModuleIdentifier> ids, final boolean isDev) {
        final String testConfName = "versionCalculator";
        final Configuration testConf = project.getConfigurations().create(testConfName);
        for (final ArtifactRepository repo : project.getRepositories()) {
            repo.content(c -> c.notForConfigurations(testConfName));
        }
        final ArtifactRepository mavenCentral = project.getRepositories().mavenCentral();
        mavenCentral.content(c -> c.onlyForConfigurations(testConfName));
        project.getRepositories().add(mavenCentral);
        final ArtifactRepository gradlePluginPortal =
                project.getRepositories().gradlePluginPortal();
        gradlePluginPortal.content(c -> c.onlyForConfigurations(testConfName));
        project.getRepositories().add(gradlePluginPortal);

        testConf.getResolutionStrategy().cacheDynamicVersionsFor(1, TimeUnit.MINUTES);
        testConf.setTransitive(false);
        for (final ModuleIdentifier id : ids) {
            testConf.getDependencies()
                    .add(
                            project.getDependencies()
                                    .create(
                                            id.getGroup()
                                                    + ":"
                                                    + id.getName()
                                                    + ":latest.integration"));
        }

        project.getLogger().lifecycle("Fetching latest version on maven repo ...");
        final Set<String> latestVersions = new HashSet<>();
        for (final ResolvedDependency dep :
                testConf.getResolvedConfiguration()
                        .getLenientConfiguration()
                        .getAllModuleDependencies()) {
            latestVersions.add(StringUtils.substringBefore(dep.getModuleVersion(), "-"));
        }
        project.getLogger().lifecycle(latestVersions.toString());

        return calculateNextVersionFromExistingVersions(latestVersions, isDev);
    }

    private static String calculateNextVersionFromExistingVersions(
            final Collection<String> latestVersions, final boolean isDev) {
        if (latestVersions.isEmpty()) {
            return "1.0.0";
        } else if (isDev) {
            // If we're publishing a dev version, we need to make sure it's lower than the latest
            // published
            // version for every involved module. We don't want to automatically bump up any of
            // them.
            return latestVersions.stream().min(IndeedOssLibraryRootPlugin::compareVersion).get();
        } else {
            // otherwise, we're going to take the latest version and add to the patch
            final String latestVersion =
                    latestVersions.stream().max(IndeedOssLibraryRootPlugin::compareVersion).get();
            final List<String> split = new ArrayList<>(Splitter.on('.').splitToList(latestVersion));
            int patchVersion = Integer.parseInt(split.get(split.size() - 1));
            patchVersion++;
            split.set(split.size() - 1, String.valueOf(patchVersion));
            return Joiner.on('.').join(split);
        }
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
