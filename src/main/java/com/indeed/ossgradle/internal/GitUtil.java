package com.indeed.ossgradle.internal;

import org.apache.commons.lang3.StringUtils;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.errors.RepositoryNotFoundException;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.gradle.api.Project;
import org.gradle.process.ExecResult;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.concurrent.atomic.AtomicReference;

public class GitUtil {

    public static String getShortHash(final Project project) {
        final AtomicReference<String> hash = new AtomicReference<>("");
        withGit(
                project,
                git -> {
                    final Iterator<RevCommit> revCommits =
                            git.log().setMaxCount(1).call().iterator();
                    if (revCommits.hasNext()) {
                        final RevCommit revCommit = revCommits.next();
                        hash.set(revCommit.abbreviate(7).name());
                        return;
                    }
                    throw new GitRepositoryException("Unable to fetch latest commit from git log");
                });
        return hash.get();
    }

    public static String getHttpUrl(final Project project) {
        String repoUrl = getOriginUrl(project);
        if (repoUrl.contains("://")) {
            repoUrl = StringUtils.substringAfter(repoUrl, "://");
        }
        if (repoUrl.contains("@")) {
            repoUrl = StringUtils.substringAfter(repoUrl, "@");
        }
        if (repoUrl.contains(".git")) {
            repoUrl = StringUtils.substringBeforeLast(repoUrl, ".git");
        }
        repoUrl = StringUtils.replace(repoUrl, ":", "/");
        repoUrl = "https://" + repoUrl;
        return repoUrl;
    }

    public static String getOriginUrl(final Project project) {
        final AtomicReference<String> repoUrl = new AtomicReference<>("");

        try {
            withGit(
                    project,
                    git -> {
                        final String repoUrlString = getOriginUrl(git);
                        if (StringUtils.isNotEmpty(repoUrlString)) {
                            repoUrl.set(repoUrlString);
                        }
                    });
        } catch (NotAGitRepositoryException e) {
            /* this is fine */
        }

        return repoUrl.get();
    }

    private static String getOriginUrl(final Git git) {
        return git.getRepository().getConfig().getString("remote", "origin", "url");
    }

    public static void withGit(final Project project, final GitConsumer func) {
        final FileRepositoryBuilder repositoryBuilder = new FileRepositoryBuilder();
        repositoryBuilder.findGitDir(project.getProjectDir());
        final File gitDir = repositoryBuilder.getGitDir();

        if (gitDir == null) {
            throw new NotAGitRepositoryException();
        }

        try (final Git git = Git.open(gitDir)) {
            func.accept(git);
        } catch (final RepositoryNotFoundException e) {
            // Currently jgit does not support "git worktree" and it will cause
            // RepositoryNotFoundException
            // see https://www.eclipse.org/forums/index.php/t/1097374/
            throw new NotAGitRepositoryException();
        } catch (final GitAPIException | IOException e) {
            throw new RuntimeException(e);
        }
    }

    public interface GitConsumer {
        void accept(final Git git) throws GitAPIException, IOException;
    }

    public static class NotAGitRepositoryException extends RuntimeException {}

    /**
     * Get current branch for given git repository
     *
     * @param path path to the git repository
     * @return current branch for given git repository
     */
    public static String getCurrentBranch(final Path path) {
        try (final Git git = Git.open(path.toFile())) {
            return git.getRepository().getBranch();
        } catch (final IOException e) {
            throw new GitRepositoryException(e);
        }
    }

    // The exception indicate that we meet some failures which operate a Git repository.
    static class GitRepositoryException extends RuntimeException {
        public GitRepositoryException(final String msg) {
            super(msg);
        }

        public GitRepositoryException(final Throwable e) {
            super(e);
        }
    }

    public static String getCurrentBranch(final Project project) {
        final String[] currentBranch = new String[1];

        withGit(
                project,
                git -> {
                    try {
                        currentBranch[0] = git.getRepository().getBranch();
                    } catch (final IOException e) {
                        throw new RuntimeException(
                                "Failed to resolve the abbreviation revision of HEAD");
                    }
                });

        return currentBranch[0];
    }

    public static String getDefaultBranch(final Project project) {
        final ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        final ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        final ExecResult result =
                project.exec(
                        exec -> {
                            exec.commandLine(
                                    "/bin/sh",
                                    "-c",
                                    "git remote show origin | awk '/HEAD branch/ {print $NF}'");
                            exec.setStandardOutput(stdout);
                            exec.setErrorOutput(stderr);
                        });
        final String output = StringUtils.trim(stdout.toString());
        if (result.getExitValue() != 0 || output.isEmpty()) {
            project.getLogger().lifecycle(stderr.toString());
            project.getLogger().lifecycle(stderr.toString());
            throw new IllegalStateException("Failed to get default branch");
        }
        return output;
    }
}
