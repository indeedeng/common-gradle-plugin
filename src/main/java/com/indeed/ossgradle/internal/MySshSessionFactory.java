package com.indeed.ossgradle.internal;

import org.apache.commons.lang3.reflect.FieldUtils;
import org.apache.commons.lang3.reflect.MethodUtils;
import org.apache.sshd.client.ClientBuilder;
import org.apache.sshd.client.SshClient;
import org.apache.sshd.client.config.hosts.HostConfigEntryResolver;
import org.apache.sshd.common.SshException;
import org.apache.sshd.common.compression.BuiltinCompressions;
import org.apache.sshd.common.keyprovider.KeyIdentityProvider;
import org.eclipse.jgit.errors.TransportException;
import org.eclipse.jgit.internal.transport.sshd.AuthenticationCanceledException;
import org.eclipse.jgit.internal.transport.sshd.JGitServerKeyVerifier;
import org.eclipse.jgit.internal.transport.sshd.JGitSshClient;
import org.eclipse.jgit.internal.transport.sshd.JGitUserInteraction;
import org.eclipse.jgit.internal.transport.sshd.agent.JGitSshAgentFactory;
import org.eclipse.jgit.internal.transport.sshd.agent.connector.Factory;
import org.eclipse.jgit.transport.CredentialsProvider;
import org.eclipse.jgit.transport.SshConstants;
import org.eclipse.jgit.transport.URIish;
import org.eclipse.jgit.transport.sshd.KeyPasswordProvider;
import org.eclipse.jgit.transport.sshd.SshdSession;
import org.eclipse.jgit.transport.sshd.SshdSessionFactory;
import org.eclipse.jgit.util.FS;

import java.io.File;
import java.lang.reflect.Constructor;
import java.time.Duration;
import java.util.ArrayList;
import java.util.function.Supplier;

public class MySshSessionFactory extends SshdSessionFactory {
    // This is pretty much all copied from the super class, except
    // we actually register the ssh agent factory, because it can't find is using
    // ServiceLoader in gradle for some reason.
    @Override
    public SshdSession getSession(final URIish uri, final CredentialsProvider credentialsProvider, final FS fs, final int tms) throws TransportException {
        SshdSession session = null;
        try {
            session = construct(SshdSession.class, uri, (Supplier<SshClient>)() -> {
                File home = getHomeDirectory();
                if (home == null) {
                    // Always use the detected filesystem for the user home!
                    // It makes no sense to have different "user home"
                    // directories depending on what file system a repository
                    // is.
                    home = FS.DETECTED.userHome();
                }
                File sshDir = getSshDirectory();
                if (sshDir == null) {
                    sshDir = new File(home, SshConstants.SSH_DIR);
                }
                HostConfigEntryResolver configFile = invoke(this, "getHostConfigEntryResolver",
                        home, sshDir);
                KeyIdentityProvider defaultKeysProvider = invoke(this, "toKeyIdentityProvider",
                        getDefaultKeys(sshDir));
                SshClient client = ClientBuilder.builder()
                        .factory(JGitSshClient::new)
                        .filePasswordProvider(invoke(this, "createFilePasswordProvider",
                                (Supplier< KeyPasswordProvider >)() -> createKeyPasswordProvider(
                                        credentialsProvider)))
                        .hostConfigEntryResolver(configFile)
                        .serverKeyVerifier(new JGitServerKeyVerifier(
                                getServerKeyDatabase(home, sshDir)))
                        .signatureFactories(invoke(this, "getSignatureFactories"))
                        .compressionFactories(
                                new ArrayList<>(BuiltinCompressions.VALUES))
                        .build();
                client.setUserInteraction(
                        new JGitUserInteraction(credentialsProvider));
                client.setUserAuthFactories(invoke(this, "getUserAuthFactories"));
                client.setKeyIdentityProvider(defaultKeysProvider);
                client.setAgentFactory(new JGitSshAgentFactory(new Factory(), home));
                // JGit-specific things:
                JGitSshClient jgitClient = (JGitSshClient) client;
                jgitClient.setKeyCache(getKeyCache());
                jgitClient.setCredentialsProvider(credentialsProvider);
                jgitClient.setProxyDatabase(readField(this, "proxies"));
                String defaultAuths = getDefaultPreferredAuthentications();
                if (defaultAuths != null) {
                    jgitClient.setAttribute(
                            JGitSshClient.PREFERRED_AUTHENTICATIONS,
                            defaultAuths);
                }
                // Other things?
                return client;
            });
            session.addCloseListener(s -> invoke(this, "unregister", s));
            invoke(this, "register", session);
            invoke(session, "connect", Duration.ofMillis(tms));
            return session;
        } catch (Exception e) {
            invoke(this, "unregister", session);
            if (e instanceof TransportException) {
                throw (TransportException) e;
            }
            Throwable cause = e;
            if (e instanceof SshException && e
                    .getCause() instanceof AuthenticationCanceledException) {
                // Results in a nicer error message
                cause = e.getCause();
            }
            throw new TransportException(uri, cause.getMessage(), cause);
        }
    }

    private static <T> T construct(final Class<T> cls, Object... args) {
        Constructor<T> constructor= (Constructor<T>) cls.getDeclaredConstructors()[0];
        constructor.setAccessible(true);
        try {
            return constructor.newInstance(args);
        } catch(final Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static <T> T invoke(
            final Object object,
            final String methodName,
            Object... args
    ) {
        try {
            return (T)MethodUtils.invokeMethod(object, true, methodName, args);
        } catch(final Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static <T> T readField(final Object target, final String fieldName) {
        try {
            return (T)FieldUtils.readField(target, fieldName, true);
        } catch(final Exception e) {
            throw new RuntimeException(e);
        }
    }
}
