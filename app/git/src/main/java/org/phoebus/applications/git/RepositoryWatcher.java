package org.phoebus.applications.git;

import java.io.File;
import java.nio.file.ClosedWatchServiceException;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

/** Watches a repository for changes and invokes a callback
 *
 *  <p>Watches the directories provided by {@link GitRepository#getWatchDirectories(int)}.
 *  Changes are collected until there have been no further changes for a short time,
 *  then the callback is invoked once.
 *  New directories are watched after the next change.
 */
@SuppressWarnings("nls")
class RepositoryWatcher implements AutoCloseable
{
    /** Maximum number of work tree directories to watch */
    static final int MAX_DIRECTORIES = 5000;

    /** Wait this long without changes before invoking the callback */
    private static final long QUIET_MS = 500;

    /** Provides the directories to watch */
    @FunctionalInterface
    interface DirectorySupplier
    {
        List<File> get() throws Exception;
    }

    private final DirectorySupplier directories;
    private final Runnable on_change;
    private final WatchService watcher;
    private final Set<Path> watched = new HashSet<>();
    private final Thread thread;

    /** @param directories Provides the directories to watch
     *  @param on_change Called on the watcher thread after changes
     */
    RepositoryWatcher(final DirectorySupplier directories, final Runnable on_change) throws Exception
    {
        this.directories = directories;
        this.on_change = on_change;
        watcher = FileSystems.getDefault().newWatchService();
        register();
        thread = new Thread(this::run, "Git Watcher");
        thread.setDaemon(true);
        thread.start();
    }

    /** @return Number of watched directories */
    int getWatchCount()
    {
        synchronized (watched)
        {
            return watched.size();
        }
    }

    /** Register directories that are not watched, yet */
    private void register() throws Exception
    {
        for (File dir : directories.get())
        {
            final Path path = dir.toPath();
            synchronized (watched)
            {
                if (watched.contains(path))
                    continue;
            }
            try
            {
                path.register(watcher,
                              StandardWatchEventKinds.ENTRY_CREATE,
                              StandardWatchEventKinds.ENTRY_DELETE,
                              StandardWatchEventKinds.ENTRY_MODIFY);
                synchronized (watched)
                {
                    watched.add(path);
                }
            }
            catch (Exception ex)
            {
                // For example the limit of inotify watches. Keep what's registered
                GitApp.logger.log(Level.WARNING, "Cannot watch " + path + ", " + getWatchCount() + " directories are watched", ex);
                return;
            }
        }
    }

    private void run()
    {
        try
        {
            while (true)
            {
                // Wait for a change, then collect changes until it's quiet
                WatchKey key = watcher.take();
                while (key != null)
                {
                    handle(key);
                    key = watcher.poll(QUIET_MS, TimeUnit.MILLISECONDS);
                }
                on_change.run();
                register();
            }
        }
        catch (ClosedWatchServiceException | InterruptedException ex)
        {
            // Closed
        }
        catch (Exception ex)
        {
            GitApp.logger.log(Level.WARNING, "Repository watcher failed", ex);
        }
    }

    /** Drain events of a key, forget directories that were deleted */
    private void handle(final WatchKey key)
    {
        // Details of the events don't matter, any change triggers a refresh
        key.pollEvents();
        if (! key.reset())
            synchronized (watched)
            {
                watched.remove((Path) key.watchable());
            }
    }

    @Override
    public void close()
    {
        try
        {
            watcher.close();
        }
        catch (Exception ex)
        {
            // Ignore
        }
        thread.interrupt();
    }
}
