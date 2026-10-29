package org.phoebus.applications.git;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Supplier;

import org.phoebus.applications.git.GitRepository.CommitFile;
import org.phoebus.framework.jobs.JobManager;

import javafx.application.Platform;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.SplitPane;
import javafx.scene.input.MouseButton;

/** Files changed by a commit, uncommitted changes, or differences between two revisions,
 *  with the diff of the selected file
 *
 *  <p>The first entry of the file list shows the diff of all files.
 */
@SuppressWarnings("nls")
class CommitFilesPane extends SplitPane
{
    /** Entry for 'all files' */
    private static final CommitFile ALL = new CommitFile(null, "", null);

    private final Supplier<GitRepository> repo;
    private final Consumer<Exception> on_error;
    private final ListView<CommitFile> files = new ListView<>();
    private final DiffPane diff;

    /** Commit that's shown, <code>null</code> for uncommitted changes */
    private String commit = null;

    /** Revisions that are compared, <code>null</code> when showing a commit or uncommitted changes */
    private Revision from = null, to = null;

    /** Paths that are compared, <code>null</code> for all */
    private Collection<String> compare_paths = null;

    /** Number of the most recent request for files, older results are dropped */
    private final AtomicLong request = new AtomicLong();

    /** @param repo Provides the current repository
     *  @param on_error Error handler
     *  @param on_file_history Called with path to show the history of a file
     *  @param on_open Called with path and 'editor' flag to open a file
     *  @param git_actions Compare, restore, ... or <code>null</code>
     */
    CommitFilesPane(final Supplier<GitRepository> repo, final Consumer<Exception> on_error,
                    final Consumer<String> on_file_history, final BiConsumer<String, Boolean> on_open,
                    final GitActions git_actions)
    {
        this.repo = repo;
        this.on_error = on_error;
        diff = new DiffPane(repo, on_error);

        files.setCellFactory(view -> new FileCell());
        files.getSelectionModel().selectedItemProperty().addListener((prop, old, file) -> showDiff(file));

        final MenuItem history = new MenuItem(Messages.FileHistory);
        history.setOnAction(event ->
        {
            final CommitFile file = files.getSelectionModel().getSelectedItem();
            if (file != null  &&  file != ALL)
                on_file_history.accept(file.path());
        });
        // Open the file in the work tree
        final MenuItem open = new MenuItem(Messages.Open);
        open.setOnAction(event -> on_open.accept(files.getSelectionModel().getSelectedItem().path(), false));
        final MenuItem edit = new MenuItem(Messages.OpenInEditor);
        edit.setOnAction(event -> on_open.accept(files.getSelectionModel().getSelectedItem().path(), true));
        final ContextMenu menu = new ContextMenu(open, edit, history);
        // Versions of the file: Compare with work tree, restore, open
        final List<MenuItem> version_items = new ArrayList<>();
        menu.setOnShowing(event ->
        {
            final CommitFile file = files.getSelectionModel().getSelectedItem();
            final boolean none = file == null  ||  file == ALL;
            history.setDisable(none);
            open.setDisable(none);
            edit.setDisable(none);
            edit.setVisible(! none  &&  OpenFiles.isDisplay(file.path()));
            menu.getItems().removeAll(version_items);
            version_items.clear();
            if (git_actions != null  &&  ! none)
            {
                final List<MenuItem> items = createVersionItems(git_actions, file);
                if (! items.isEmpty())
                {
                    version_items.add(new SeparatorMenuItem());
                    version_items.addAll(items);
                }
            }
            menu.getItems().addAll(version_items);
        });
        files.setOnMouseClicked(event ->
        {
            final CommitFile file = files.getSelectionModel().getSelectedItem();
            if (event.getButton() == MouseButton.PRIMARY  &&  event.getClickCount() == 2  &&
                file != null  &&  file != ALL)
                on_open.accept(file.path(), false);
        });
        files.setContextMenu(menu);

        getItems().setAll(files, diff);
        setDividerPositions(0.3);
    }

    /** @param git_actions Actions
     *  @param file Selected file
     *  @return Menu items to compare, restore, open versions of the file
     */
    private List<MenuItem> createVersionItems(final GitActions git_actions, final CommitFile file)
    {
        final List<MenuItem> items = new ArrayList<>();
        // Commit and its parent, or the compared revisions
        final List<Revision.Commit> versions = new ArrayList<>();
        if (from == null  &&  commit != null)
            versions.add(new Revision.Commit(commit, commit.substring(0, 7)));
        else
        {
            if (from instanceof Revision.Commit c)
                versions.add(c);
            if (to instanceof Revision.Commit c)
                versions.add(c);
        }
        for (Revision.Commit version : versions)
        {
            // Path of the file in that version, the old path of a renamed file in the 'from' version
            final String source = version == from  &&  file.old_path() != null ? file.old_path() : file.path();
            final boolean exists = ! (file.type() == GitRepository.ChangeType.DELETED  &&  version != from)  &&
                                   ! (file.type() == GitRepository.ChangeType.ADDED  &&  version == from);
            final String label = versions.size() > 1 ? " (" + version.label() + ")" : "";
            if (! (to instanceof Revision.WorkTree))
            {
                final MenuItem compare = new MenuItem(Messages.CompareWithWorkTree + label);
                compare.setOnAction(event -> git_actions.compare(version, Revision.WORK_TREE, source.equals(file.path())
                                                                                           ? List.of(file.path())
                                                                                           : List.of(file.path(), source)));
                items.add(compare);
            }
            final MenuItem restore = new MenuItem(Messages.RestoreThisVersion + label);
            restore.setOnAction(event -> git_actions.restore(List.of(file.path()), source, version));
            items.add(restore);
            final MenuItem open_version = new MenuItem(Messages.OpenThisVersion + label);
            open_version.setOnAction(event -> git_actions.openVersion(source, version));
            open_version.setDisable(! exists);
            items.add(open_version);
        }
        if (commit == null  &&  from == null)
        {
            // Uncommitted changes: Compare or restore like in the file status
            final Menu compare = git_actions.createCompareMenu(() -> List.of(file.path()));
            final Menu restore = git_actions.createRestoreMenu(() -> List.of(file.path()));
            items.add(compare);
            items.add(restore);
        }
        return items;
    }

    /** List the files that differ between two revisions and show their diff
     *  @param a Old side
     *  @param b New side
     *  @param paths Files to compare, <code>null</code> for all
     */
    void showCompare(final Revision a, final Revision b, final Collection<String> paths)
    {
        final String keep = getSelectedPath();
        from = a;
        to = b;
        compare_paths = paths;
        commit = "";
        final GitRepository current = repo.get();
        if (current == null)
            return;
        final long this_request = request.incrementAndGet();
        JobManager.schedule(Messages.Compare, monitor ->
        {
            try
            {
                final List<CommitFile> changed = current.compare(a, b, paths);
                Platform.runLater(() ->
                {
                    if (this_request == request.get())
                        setFiles(changed, keep);
                });
            }
            catch (Exception ex)
            {
                on_error.accept(ex);
            }
        });
    }

    /** @return Number of listed files, without the 'all' entry */
    int getFileCount()
    {
        return Math.max(0, files.getItems().size() - 1);
    }

    /** List the files of a commit and show its diff
     *  @param id Commit id
     */
    void showCommit(final String id)
    {
        final String keep = id.equals(commit) ? getSelectedPath() : null;
        commit = id;
        from = to = null;
        final GitRepository current = repo.get();
        if (current == null)
            return;
        final long this_request = request.incrementAndGet();
        JobManager.schedule(Messages.Diff, monitor ->
        {
            try
            {
                final List<CommitFile> changed = current.getCommitFiles(id);
                Platform.runLater(() ->
                {
                    if (this_request == request.get())
                        setFiles(changed, keep);
                });
            }
            catch (Exception ex)
            {
                on_error.accept(ex);
            }
        });
    }

    /** List the uncommitted changes and show their diff
     *  @param changed Changed files
     */
    void showUncommitted(final List<CommitFile> changed)
    {
        final String keep = commit == null ? getSelectedPath() : null;
        commit = null;
        from = to = null;
        request.incrementAndGet();
        setFiles(changed, keep);
    }

    /** @return Path of selected file, <code>null</code> if none or 'all' */
    private String getSelectedPath()
    {
        final CommitFile selected = files.getSelectionModel().getSelectedItem();
        return selected == null  ||  selected == ALL ? null : selected.path();
    }

    /** Clear file list and diff */
    void clear()
    {
        commit = "";
        from = to = null;
        request.incrementAndGet();
        files.getItems().clear();
        diff.clear();
    }

    /** @param changed Files to list
     *  @param keep Path of file to select if still listed, otherwise 'all' is selected
     */
    private void setFiles(final List<CommitFile> changed, final String keep)
    {
        final List<CommitFile> entries = new ArrayList<>(changed.size() + 1);
        entries.add(ALL);
        entries.addAll(changed);
        files.getItems().setAll(entries);
        // Selecting 'all' shows the complete diff
        final CommitFile select = entries.stream()
                                         .filter(f -> f != ALL  &&  f.path().equals(keep))
                                         .findFirst()
                                         .orElse(ALL);
        files.getSelectionModel().clearSelection();
        files.getSelectionModel().select(select);
    }

    private void showDiff(final CommitFile file)
    {
        if (file == null)
            return;
        final String id = commit;
        if (from != null)
        {
            final Revision a = from, b = to;
            if (file == ALL)
            {
                final Collection<String> paths = compare_paths;
                diff.show(git -> git.getDiff(a, b, paths));
            }
            else
            {
                final List<String> paths = file.old_path() == null ? List.of(file.path()) : List.of(file.path(), file.old_path());
                diff.show(git -> git.getDiff(a, b, paths), null,
                          BobDiff.supports(file.path()) ? git -> git.getVersions(a, b, file) : null);
            }
        }
        else if (id == null)
        {
            // Uncommitted changes: All changed files, or one of them
            final List<String> paths = file == ALL
                ? files.getItems().stream().filter(f -> f != ALL).map(CommitFile::path).toList()
                : List.of(file.path());
            final String path = file.path();
            diff.show(git -> git.getUncommittedDiff(paths), null,
                      file != ALL  &&  BobDiff.supports(path) ? git -> git.getUncommittedVersions(path) : null);
        }
        else if (file == ALL)
            diff.show(git -> git.getCommitDiff(id));
        else
            diff.show(git -> git.getCommitFileDiff(id, file.path()), null,
                      BobDiff.supports(file.path()) ? git -> git.getCommitFileVersions(id, file.path()) : null);
    }

    /** File list entry: Colored change type and path */
    private class FileCell extends ListCell<CommitFile>
    {
        @Override
        protected void updateItem(final CommitFile file, final boolean empty)
        {
            super.updateItem(file, empty);
            if (empty  ||  file == null)
            {
                setText(null);
                setStyle(null);
                return;
            }
            if (file == ALL)
            {
                setText(Messages.AllFiles + " (" + (files.getItems().size() - 1) + ")");
                setStyle("-fx-font-style: italic;");
                return;
            }
            setText(file.old_path() == null
                    ? file.type() + "  " + file.path()
                    : file.type() + "  " + file.old_path() + " → " + file.path());
            setStyle("-fx-text-fill: " + FileStatusPane.getColor(file.type()) + ";");
        }
    }
}
