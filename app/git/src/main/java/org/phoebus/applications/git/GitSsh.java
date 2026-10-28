package org.phoebus.applications.git;

import java.io.File;
import java.util.List;

import org.eclipse.jgit.internal.transport.sshd.CachingKeyPairProvider;
import org.eclipse.jgit.transport.SshSessionFactory;
import org.eclipse.jgit.transport.sshd.JGitKeyCache;
import org.eclipse.jgit.transport.sshd.SshdSessionFactoryBuilder;
import org.eclipse.jgit.util.FS;

/** SSH support based on Apache MINA sshd
 *
 *  <p>Keys, known_hosts and config are read from the
 *  configured SSH directory.
 *  Key passphrases and unknown host keys are handled via
 *  the CredentialsProvider of the git command.
 */
class GitSsh
{
    private static SshSessionFactory factory;

    /** @return Shared SSH session factory */
    static synchronized SshSessionFactory getFactory()
    {
        if (factory == null)
        {
            final JGitKeyCache cache = new JGitKeyCache();
            final SshdSessionFactoryBuilder builder = new SshdSessionFactoryBuilder()
                .setHomeDirectory(FS.DETECTED.userHome())
                .setSshDirectory(GitApp.ssh_directory);
            if (! GitApp.ssh_private_key.isBlank())
            {
                final File key = new File(GitApp.ssh_private_key).getAbsoluteFile();
                builder.setDefaultKeysProvider(dir -> new CachingKeyPairProvider(List.of(key.toPath()), cache));
            }
            factory = builder.build(cache);
        }
        return factory;
    }
}
