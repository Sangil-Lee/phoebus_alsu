package org.phoebus.applications.git;

import java.io.File;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Level;

import org.eclipse.jgit.lib.Constants;
import org.phoebus.applications.git.GitRepository.ChangeType;
import org.phoebus.applications.git.GitRepository.FileStates;
import org.phoebus.framework.jobs.JobManager;
import org.phoebus.ui.spi.FileDecoration;
import org.phoebus.ui.spi.FileDecorator;

/** Decorates files in the File Browser with their git state
 *
 *  <p>For each repository that the File Browser shows, the state of the files
 *  is read in the background and kept up to date by watching the repository.
 *  {@link #getDecoration(File)} only uses that cached state.
 */
@SuppressWarnings("nls")
public class GitFileDecorator implements FileDecorator
{
    /** Maximum number of repositories that are tracked, least recently used ones are dropped */
    static final int MAX_REPOSITORIES = 20;

    /** Marker for 'not in a work tree' */
    private static final File NONE = new File("");

    /** Snapshot of a repository's state
     *  @param changes Changed files by path
     *  @param dirty_dirs Directories that contain changed files
     *  @param ignored Ignored files and directories
     *  @param label Branch information for the top directory
     */
    record Snapshot(Map<String, ChangeType> changes, Set<String> dirty_dirs, Set<String> ignored, String label)
    {
        /** @param states File states
         *  @return Snapshot
         */
        static Snapshot of(final FileStates states)
        {
            final Set<String> dirs = new HashSet<>();
            for (Map.Entry<String, ChangeType> entry : states.changes().entrySet())
            {
                // Ignored files are not 'changes' of their directory
                String path = entry.getKey();
                for (int sep = path.lastIndexOf('/'); sep > 0; sep = path.lastIndexOf('/'))
                {
                    path = path.substring(0, sep);
                    if (! dirs.add(path))
                        break;
                }
            }
            final StringBuilder label = new StringBuilder(states.branch());
            if (states.ahead() > 0)
                label.append(" ↑").append(states.ahead());
            if (states.behind() > 0)
                label.append(" ↓").append(states.behind());
            if (! states.changes().isEmpty())
                label.append(" *");
            return new Snapshot(Map.copyOf(states.changes()), dirs, states.ignored(), label.toString());
        }

        /** @param path Path relative to work tree
         *  @return Is the path or one of its directories ignored?
         */
        boolean isIgnored(final String path)
        {
            if (ignored.contains(path))
                return true;
            for (int sep = path.lastIndexOf('/'); sep > 0; sep = path.lastIndexOf('/', sep - 1))
                if (ignored.contains(path.substring(0, sep)))
                    return true;
            return false;
        }

        /** @param path Path relative to work tree, empty for the work tree itself
         *  @param directory Is it a directory?
         *  @return Decoration or <code>null</code>
         */
        FileDecoration decorate(final String path, final boolean directory)
        {
            if (path.isEmpty())
                return new FileDecoration(null, "", "  [" + label + "]", "-fx-font-weight: bold;");
            final ChangeType type = changes.get(path);
            if (type != null)
                return switch (type)
                {
                    case CONFLICT  -> new FileDecoration(null, "! ", "", "-fx-text-fill: #c00000; -fx-font-weight: bold;");
                    case ADDED     -> new FileDecoration(null, "+ ", "", "-fx-text-fill: #00802b;");
                    case UNTRACKED -> new FileDecoration(null, "? ", "", "-fx-text-fill: #707070;");
                    case DELETED   -> new FileDecoration(null, "- ", "", "-fx-text-fill: #c00000;");
                    default        -> new FileDecoration(null, "> ", "", "-fx-text-fill: #a05000;");
                };
            if (directory  &&  dirty_dirs.contains(path))
                return new FileDecoration(null, "> ", "", "-fx-text-fill: #a05000;");
            if (isIgnored(path))
                return new FileDecoration(null, "", "", "-fx-text-fill: #a0a0a0;");
            return null;
        }
    }

    /** Tracked repository */
    private class Entry
    {
        final File work_tree;
        private GitRepository repo = null;
        private RepositoryWatcher watcher = null;
        private volatile boolean closed = false;
        volatile Snapshot snapshot = null;

        Entry(final File work_tree)
        {
            this.work_tree = work_tree;
            JobManager.schedule(Messages.GitStatus, monitor -> start());
        }

        private synchronized void start()
        {
            try
            {
                if (closed)
                    return;
                repo = new GitRepository(work_tree);
                read();
                final GitRepository current = repo;
                // Reading the state does not modify the repository, so it can be read on each change
                watcher = new RepositoryWatcher(() -> current.getWatchDirectories(RepositoryWatcher.MAX_DIRECTORIES),
                                                this::read);
            }
            catch (Exception ex)
            {
                GitApp.logger.log(Level.WARNING, "Cannot read git state of " + work_tree, ex);
            }
        }

        private void read()
        {
            final GitRepository current = repo;
            if (current == null  ||  closed)
                return;
            try
            {
                final Snapshot update = Snapshot.of(current.getFileStates());
                if (! update.equals(snapshot))
                {
                    snapshot = update;
                    notifyListeners();
                }
            }
            catch (Exception ex)
            {
                GitApp.logger.log(Level.FINE, "Cannot read git state of " + work_tree, ex);
            }
        }

        synchronized void close()
        {
            closed = true;
            if (watcher != null)
                watcher.close();
            if (repo != null)
                repo.close();
            watcher = null;
            repo = null;
        }
    }

    private final List<Runnable> listeners = new CopyOnWriteArrayList<>();

    /** Directory to its work tree, or NONE */
    private final Map<File, File> work_trees = new ConcurrentHashMap<>();

    /** Tracked repositories by work tree, least recently used first */
    private final Map<File, Entry> repositories = new LinkedHashMap<>(16, 0.75f, true);

    @Override
    public void addListener(final Runnable on_change)
    {
        listeners.add(on_change);
    }

    @Override
    public void removeListener(final Runnable on_change)
    {
        listeners.remove(on_change);
    }

    private void notifyListeners()
    {
        for (Runnable listener : listeners)
            listener.run();
    }

    /** @param dir Directory
     *  @return Work tree that contains the directory, or NONE
     */
    File findWorkTree(final File dir)
    {
        if (dir == null)
            return NONE;
        final File known = work_trees.get(dir);
        if (known != null)
            return known;
        final File result = new File(dir, Constants.DOT_GIT).exists() ? dir : findWorkTree(dir.getParentFile());
        work_trees.put(dir, result);
        return result;
    }

    /** @param work_tree Work tree
     *  @return Snapshot of the repository, <code>null</code> while it's being read
     */
    private Snapshot getSnapshot(final File work_tree)
    {
        Entry entry, dropped = null;
        synchronized (repositories)
        {
            entry = repositories.get(work_tree);
            if (entry == null)
            {
                entry = new Entry(work_tree);
                repositories.put(work_tree, entry);
                if (repositories.size() > MAX_REPOSITORIES)
                {
                    final File eldest = repositories.keySet().iterator().next();
                    dropped = repositories.remove(eldest);
                }
            }
        }
        if (dropped != null)
            dropped.close();
        return entry.snapshot;
    }

    /** @return Number of tracked repositories */
    int getRepositoryCount()
    {
        synchronized (repositories)
        {
            return repositories.size();
        }
    }

    @Override
    public FileDecoration getDecoration(final File file)
    {
        if (! GitApp.file_browser_decorations)
            return null;
        final File absolute = file.getAbsoluteFile();
        final boolean directory = absolute.isDirectory();
        final File work_tree = findWorkTree(directory ? absolute : absolute.getParentFile());
        if (work_tree == NONE)
            return null;
        final Snapshot snapshot = getSnapshot(work_tree);
        if (snapshot == null)
            return null;
        final String path = work_tree.toPath().relativize(absolute.toPath()).toString().replace(File.separatorChar, '/');
        if (path.equals(Constants.DOT_GIT)  ||  path.startsWith(Constants.DOT_GIT + "/"))
            return null;
        return snapshot.decorate(path, directory);
    }

    /** Stop tracking all repositories */
    void close()
    {
        synchronized (repositories)
        {
            repositories.values().forEach(Entry::close);
            repositories.clear();
        }
        work_trees.clear();
    }
}
