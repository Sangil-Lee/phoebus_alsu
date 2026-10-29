package org.phoebus.applications.git;

import org.eclipse.jgit.lib.Config;
import org.eclipse.jgit.storage.file.FileBasedConfig;
import org.eclipse.jgit.storage.file.WindowCacheConfig;
import org.eclipse.jgit.util.FS;
import org.eclipse.jgit.util.SystemReader;
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

/** Test environment for all tests, registered via auto-detection
 *
 *  <p>Tests must not depend on the system's git configuration,
 *  for example core.autocrlf=true of Git for Windows.
 *  After each test, pack files that JGit keeps open are closed,
 *  otherwise Windows cannot delete the temporary repositories.
 */
public class GitTestSupport implements BeforeAllCallback, AfterEachCallback
{
    /** System reader without system configuration */
    private static class NoSystemConfig extends SystemReader
    {
        private final SystemReader delegate;

        NoSystemConfig(final SystemReader delegate)
        {
            this.delegate = delegate;
        }

        @Override
        public String getHostname()
        {
            return delegate.getHostname();
        }

        @Override
        public String getenv(final String variable)
        {
            return delegate.getenv(variable);
        }

        @Override
        public String getProperty(final String key)
        {
            return delegate.getProperty(key);
        }

        @Override
        public FileBasedConfig openUserConfig(final Config parent, final FS fs)
        {
            return delegate.openUserConfig(parent, fs);
        }

        @Override
        public FileBasedConfig openSystemConfig(final Config parent, final FS fs)
        {
            return new FileBasedConfig(parent, null, fs)
            {
                @Override
                public void load()
                {
                    // Empty
                }

                @Override
                public boolean isOutdated()
                {
                    return false;
                }
            };
        }

        @Override
        public FileBasedConfig openJGitConfig(final Config parent, final FS fs)
        {
            return delegate.openJGitConfig(parent, fs);
        }

        @Override
        public long getCurrentTime()
        {
            return delegate.getCurrentTime();
        }

        @Override
        public int getTimezone(final long when)
        {
            return delegate.getTimezone(when);
        }
    }

    @Override
    public void beforeAll(final ExtensionContext context)
    {
        if (! (SystemReader.getInstance() instanceof NoSystemConfig))
            SystemReader.setInstance(new NoSystemConfig(SystemReader.getInstance()));
    }

    @Override
    public void afterEach(final ExtensionContext context)
    {
        // Closes cached pack files
        new WindowCacheConfig().install();
    }
}
