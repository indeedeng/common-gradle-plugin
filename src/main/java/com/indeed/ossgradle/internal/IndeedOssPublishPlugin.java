package com.indeed.ossgradle.internal;

import com.google.common.base.Joiner;
import com.google.common.base.Splitter;
import com.google.common.base.Suppliers;
import com.gradle.publish.PluginBundleExtension;
import com.gradle.publish.PublishPlugin;
import org.apache.commons.lang3.StringUtils;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.Task;
import org.gradle.api.internal.artifacts.ivyservice.ivyresolve.strategy.DefaultVersionComparator;
import org.gradle.api.internal.artifacts.ivyservice.ivyresolve.strategy.Version;
import org.gradle.api.internal.artifacts.ivyservice.ivyresolve.strategy.VersionParser;
import org.gradle.api.plugins.ExtraPropertiesExtension;
import org.gradle.api.plugins.JavaPluginExtension;
import org.gradle.api.publish.PublishingExtension;
import org.gradle.api.publish.maven.MavenPublication;
import org.gradle.api.publish.maven.plugins.MavenPublishPlugin;
import org.gradle.api.publish.maven.tasks.PublishToMavenLocal;
import org.gradle.api.publish.maven.tasks.PublishToMavenRepository;
import org.gradle.api.tasks.TaskProvider;
import org.gradle.api.tasks.javadoc.Javadoc;
import org.gradle.external.javadoc.CoreJavadocOptions;
import org.gradle.plugin.devel.plugins.JavaGradlePluginPlugin;

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

public class IndeedOssPublishPlugin implements Plugin<Project> {

    public static final String PUBLOCAL_VERSION_PREFIX = "0.local.";
    private static final DateTimeFormatter localVersionFormatter =
            DateTimeFormatter.ofPattern("yyyyMMddHHmmss").withZone(ZoneOffset.UTC);
    private static final String TAG_PREFIX = "published/";
    private static final Comparator<Version> VERSION_COMPARATOR = new DefaultVersionComparator().asVersionComparator();
    private static final VersionParser VERSION_PARSER = new VersionParser();

    @Override
    public void apply(final Project rootProject) {
        if (rootProject != rootProject.getRootProject()) {
            throw new IllegalStateException("This can only be applied to the root project");
        }

        final boolean local = getCiWorkspace(rootProject) == null;

        final Supplier<String> versionSupplier = Suppliers.memoize(() -> {
            final boolean isRc = !local && !StringUtils.equals(GitUtil.getDefaultBranch(rootProject), GitUtil.getCurrentBranch(rootProject));
            rootProject.getLogger().lifecycle("Calculating next version ...");
            if (!local && !isRc) {
                rootProject.getLogger().warn("Detected default branch on CI - we're in full publish go mode");
            }
            final String version = calculateVersion(rootProject, local, isRc);
            rootProject.getLogger().lifecycle(version);
            return version;
        });
        final Supplier<String> httpUrlSupplier = Suppliers.memoize(() -> GitUtil.getHttpUrl(rootProject));
        final Supplier<TaskProvider<Task>> pushTagTask = Suppliers.memoize(() ->
            rootProject.getTasks().register("pushPublishTag", task -> {
                task.doFirst(t -> {
                    if (local) {
                        return;
                    }
                    final String version = versionSupplier.get();
                    GitUtil.commitGitTag(rootProject, TAG_PREFIX + version, "Publishing "
                            + version);
                });
            })
        );

        rootProject.allprojects(p -> {
            if (p.getPlugins().hasPlugin(JavaGradlePluginPlugin.class)) {
                p.getPlugins().apply(PublishPlugin.class);
            }
            p.afterEvaluate(p2 -> setupPublishing(p, local, versionSupplier, httpUrlSupplier, pushTagTask));
        });
    }

    private String calculateVersion(final Project project, final boolean local, final boolean isRc) {
        if (local) {
            return PUBLOCAL_VERSION_PREFIX
                            + localVersionFormatter.format(Instant.now());
        }

        final Set<String> tags = new HashSet<>(GitUtil.getTags(project));

        final String latestVersion =
                tags.stream()
                        .filter(
                                (tag) ->
                                        tag.matches(Pattern.quote(TAG_PREFIX) + "(.*\\.[0-9]+)?"))
                        .filter((tag) -> !tag.contains("-rc"))
                        .map((tag) -> tag.substring(TAG_PREFIX.length()))
                        .max(IndeedOssPublishPlugin::compareVersion)
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
        return VERSION_COMPARATOR.compare(
                VERSION_PARSER.transform(a),
                VERSION_PARSER.transform(b)
        );
    }

    private void setupPublishing(
            final Project project,
            final boolean local,
            final Supplier<String> versionSupplier,
            final Supplier<String> httpUrlSupplier,
            final Supplier<TaskProvider<Task>> pushTagTaskSupplier
    ) {
        final ExtraPropertiesExtension ext = project.getExtensions()
                .getByType(ExtraPropertiesExtension.class);
        if (!ext.has("indeed.publish.name")) {
            return;
        }

        final boolean isGradlePlugin = project.getPlugins().hasPlugin(JavaGradlePluginPlugin.class);
        final JavaPluginExtension javaExt = project.getExtensions().getByType(JavaPluginExtension.class);

        if (isGradlePlugin) {
            javaExt.withSourcesJar();
        } else if (!local) {
            javaExt.withJavadocJar();
        }

        final String publishVersion = versionSupplier.get();

        // Set up publication
        project.getPluginManager().apply(MavenPublishPlugin.class);
        final PublishingExtension publishingExt =
                project.getExtensions().getByType(PublishingExtension.class);

        final String publishGroup;
        if (ext.has("indeed.publish.group")) {
            publishGroup = (String)ext.get("indeed.publish.group");
        } else {
            publishGroup = "com.indeed";
        }
        final String publishName = (String)ext.get("indeed.publish.name");

        if (isGradlePlugin) {
            project.getExtensions().getByType(PluginBundleExtension.class).getPlugins().configureEach(plugin -> {
                plugin.setVersion(publishVersion);
            });
        }

        final String publicationName;
        if (isGradlePlugin) {
            publicationName = "pluginMaven";
            publishingExt.getPublications().withType(MavenPublication.class).configureEach(publication -> {
                if (publication.getName().equals("ossPluginPluginMarkerMaven")) {
                    publication.setVersion(publishVersion);
                }
            });
        } else {
            publicationName = "maven";
            publishingExt.getPublications().create(publicationName, MavenPublication.class);
        }

        publishingExt.getPublications().withType(MavenPublication.class).configureEach(publication -> {
            if (!publication.getName().equals(publicationName)) {
                return;
            }
            publication.setGroupId(publishGroup);
            publication.setArtifactId(publishName);
            publication.setVersion(publishVersion);
            publication.versionMapping(mapping ->
                    mapping.allVariants(
                            strategy ->
                                    strategy.fromResolutionResult())
            );
            if (isGradlePlugin) {
                // Fix for https://github.com/gradle/gradle/issues/19331
                project.getTasks().named("generateMetadataFileForPluginMavenPublication").configure(task -> {
                    task.dependsOn("publishPluginJar");
                    task.dependsOn("publishPluginJavaDocsJar");
                });
            }
            if (!isGradlePlugin) {
                configurePublicationMetadata(project, publication, httpUrlSupplier, publishName);
            }
        });

        if (isGradlePlugin && !local) {
            project.getTasks().named("publish").configure(task -> {
                task.dependsOn("publishPlugins");
            });
        } else {
            publishingExt
                    .getRepositories()
                    .maven(
                            (repo) -> {
                                repo.setName("maven");
                                if (local || true) {
                                    repo.setUrl(System.getenv("HOME") + "/.m2/repository");
                                } else {
                                    repo.setUrl(getCiWorkspace(project).resolve("maven-publish"));
                                }
                            });
        }

        final TaskProvider<Task> pushTagTask;
        if (!local) {
            pushTagTask = pushTagTaskSupplier.get();
        } else {
            pushTagTask = null;
        }

        project.getTasks().configureEach(task -> {
            if (task instanceof PublishToMavenRepository || task instanceof PublishToMavenLocal || task.getName().equals("publishPlugins")) {
                task.doFirst(t -> {
                    if (!project.getGradle().getTaskGraph().hasTask(project.getRootProject().getTasks().getByName("publish"))) {
                        throw new IllegalArgumentException("Publishing should only be done by running `gradle publish`");
                    }
                });
                if (pushTagTask != null) {
                    task.finalizedBy(pushTagTask);
                    pushTagTask.get().dependsOn(task);
                }
            }
        });

        project.getTasks().withType(Javadoc.class).configureEach(task -> {
            task.getOptions().quiet();
            ((CoreJavadocOptions) (task.getOptions())).addBooleanOption("Xdoclint:none", true);
        });
    }

    private static void configurePublicationMetadata(
            final Project project,
            final MavenPublication publication,
            final Supplier<String> httpUrlSupplier,
            final String publishName
    ) {
        final String httpUrl = httpUrlSupplier.get();
        final String scmUrl = StringUtils.replaceOnce(StringUtils.replace(httpUrl, "https://", "scm:git:git@"), "/", ":") + ".git";

        publication.getPom().getName().set(publishName);
        publication.getPom().getDescription().set(publishName);
        publication.getPom().getUrl().set(httpUrl);
        publication
                .getPom()
                .developers(
                        (devs) ->
                                devs.developer(
                                        (dev) -> {
                                            dev.getId().set("IndeedEng");
                                            dev.getName().set("Indeed Engineering");
                                            dev.getUrl().set("https://github.com/indeedeng");
                                        })
                );
        publication
                .getPom()
                .licenses(
                        (lics) ->
                                lics.license(
                                        (lic) -> {
                                            lic.getName()
                                                    .set(
                                                            "The Apache Software License, Version 2.0");
                                            lic.getUrl()
                                                    .set(
                                                            "http://www.apache.org/licenses/LICENSE-2.0.txt");
                                        })
                );
        publication
                .getPom()
                .scm(
                        (scm) -> {
                            scm.getUrl().set(httpUrl);
                            scm.getConnection()
                                    .set(scmUrl);
                            scm.getDeveloperConnection()
                                    .set(scmUrl);
                        });
    }

    @Nullable
    private static Path getCiWorkspace(final Project project) {
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
}
