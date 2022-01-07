package com.indeed.ossgradle.internal;

import org.apache.commons.lang3.StringUtils;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.plugins.JavaLibraryPlugin;
import org.gradle.api.plugins.JavaPluginExtension;
import org.gradle.api.publish.PublishingExtension;
import org.gradle.api.publish.maven.MavenPublication;
import org.gradle.api.publish.maven.plugins.MavenPublishPlugin;
import org.gradle.api.publish.maven.tasks.PublishToMavenLocal;
import org.gradle.api.publish.maven.tasks.PublishToMavenRepository;
import org.gradle.api.tasks.javadoc.Javadoc;
import org.gradle.external.javadoc.CoreJavadocOptions;

import java.nio.file.Path;
import java.util.function.Supplier;

/** Applied if the current project is a publishable library */
public class IndeedOssLibraryPlugin implements Plugin<Project> {
    private Project project;

    @Override
    public void apply(final Project project) {
        this.project = project;
        project.getExtensions().create("indeedLibrary", IndeedOssLibraryExtension.class, project);
        project.getPlugins().apply(JavaLibraryPlugin.class);
        project.afterEvaluate(p -> afterEvaluate());
    }

    public void onVersionReady() {
        final IndeedOssLibraryRootPlugin rootPlugin =
                project.getRootProject().getPlugins().apply(IndeedOssLibraryRootPlugin.class);
        project.setVersion(rootPlugin.getVersion());
    }

    private void afterEvaluate() {
        final IndeedOssLibraryExtension ext =
                project.getExtensions().findByType(IndeedOssLibraryExtension.class);
        final IndeedOssLibraryRootPlugin rootPlugin =
                project.getRootProject().getPlugins().apply(IndeedOssLibraryRootPlugin.class);
        final boolean local = rootPlugin.getIsLocalPublish();
        final Supplier<String> httpUrlSupplier = () -> rootPlugin.getHttpUrl();
        final Path ciWorkspace = rootPlugin.getCiWorkspace();
        final boolean isGradlePlugin =
                project.getPlugins().hasPlugin(IndeedOssGradlePluginPlugin.class);
        final JavaPluginExtension javaExt =
                project.getExtensions().getByType(JavaPluginExtension.class);

        if (!local) {
            javaExt.withSourcesJar();
            javaExt.withJavadocJar();
        }

        // Set up publication
        project.getPluginManager().apply(MavenPublishPlugin.class);
        final PublishingExtension publishingExt =
                project.getExtensions().getByType(PublishingExtension.class);

        final String publishGroup = ext.getGroup().get();
        final String publishName = ext.getName().get();

        final String publicationName;
        if (isGradlePlugin) {
            publicationName = "pluginMaven";
        } else {
            publicationName = "maven";
            publishingExt.getPublications().create(publicationName, MavenPublication.class);
        }

        publishingExt
                .getPublications()
                .withType(MavenPublication.class)
                .configureEach(
                        publication -> {
                            if (!publication.getName().equals(publicationName)) {
                                return;
                            }
                            publication.setGroupId(publishGroup);
                            publication.setArtifactId(publishName);
                            publication.versionMapping(
                                    mapping ->
                                            mapping.allVariants(
                                                    strategy -> strategy.fromResolutionResult()));
                            if (isGradlePlugin) {
                                // Fix for https://github.com/gradle/gradle/issues/19331
                                project.getTasks()
                                        .named("generateMetadataFileForPluginMavenPublication")
                                        .configure(
                                                task -> {
                                                    task.dependsOn("publishPluginJar");
                                                    task.dependsOn("publishPluginJavaDocsJar");
                                                });
                            }
                            if (!isGradlePlugin) {
                                configurePublicationMetadata(
                                        project, publication, httpUrlSupplier, publishName);
                                publication.from(project.getComponents().getByName("java"));
                            }
                        });

        if (isGradlePlugin && !local) {
            project.getTasks()
                    .named("publish")
                    .configure(
                            task -> {
                                task.dependsOn("publishPlugins");
                            });
        } else {
            publishingExt
                    .getRepositories()
                    .maven(
                            (repo) -> {
                                repo.setName("maven");
                                if (local) {
                                    repo.setUrl(System.getenv("HOME") + "/.m2/repository");
                                } else {
                                    repo.setUrl(ciWorkspace.resolve("maven-publish"));
                                }
                            });
        }

        project.getTasks()
                .configureEach(
                        task -> {
                            if (task instanceof PublishToMavenRepository
                                    || task instanceof PublishToMavenLocal
                                    || task.getName().equals("publishPlugins")) {
                                task.doFirst(
                                        t -> {
                                            if (!project.getGradle()
                                                    .getTaskGraph()
                                                    .hasTask(
                                                            project.getTasks()
                                                                    .getByName("publish"))) {
                                                throw new IllegalArgumentException(
                                                        "Publishing should only be done by running `gradle publish`");
                                            }
                                        });
                            }
                        });

        project.getTasks()
                .withType(Javadoc.class)
                .configureEach(
                        task -> {
                            task.getOptions().quiet();
                            ((CoreJavadocOptions) (task.getOptions()))
                                    .addBooleanOption("Xdoclint:none", true);
                        });
    }

    private static void configurePublicationMetadata(
            final Project project,
            final MavenPublication publication,
            final Supplier<String> httpUrlSupplier,
            final String publishName) {
        final String httpUrl = httpUrlSupplier.get();
        final String scmUrl =
                StringUtils.replaceOnce(
                                StringUtils.replace(httpUrl, "https://", "scm:git:git@"), "/", ":")
                        + ".git";

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
                                        }));
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
                                        }));
        publication
                .getPom()
                .scm(
                        (scm) -> {
                            scm.getUrl().set(httpUrl);
                            scm.getConnection().set(scmUrl);
                            scm.getDeveloperConnection().set(scmUrl);
                        });
    }
}
