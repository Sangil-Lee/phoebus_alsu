package org.phoebus.applications.git;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.Supplier;

import org.phoebus.applications.git.GitRepository.CommitFile;
import org.phoebus.framework.jobs.JobManager;

import javafx.application.Platform;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SplitPane;

/** Files changed by a commit, or uncommitted changes, with the diff of the selected file
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

    /** Number of the most recent request for files, older results are dropped */
    private final AtomicLong request = new AtomicLong();

    /** @param repo Provides the current repository
     *  @param on_error Error handler
     *  @param on_file_history Called with path to show the history of a file
     */
    CommitFilesPane(final Supplier<GitRepository> repo, final Consumer<Exception> on_error,
                    final Consumer<String> on_file_history)
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
        final ContextMenu menu = new ContextMenu(history);
        menu.setOnShowing(event ->
        {
            final CommitFile file = files.getSelectionModel().getSelectedItem();
            history.setDisable(file == null  ||  file == ALL);
        });
        files.setContextMenu(menu);

        getItems().setAll(files, diff);
        setDividerPositions(0.3);
    }

    /** List the files of a commit and show its diff
     *  @param id Commit id
     */
    void showCommit(final String id)
    {
        final String keep = id.equals(commit) ? getSelectedPath() : null;
        commit = id;
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
        if (id == null)
        {
            // Uncommitted changes: All changed files, or one of them
            final List<String> paths = file == ALL
                ? files.getItems().stream().filter(f -> f != ALL).map(CommitFile::path).toList()
                : List.of(file.path());
            diff.show(git -> git.getUncommittedDiff(paths));
        }
        else if (file == ALL)
            diff.show(git -> git.getCommitDiff(id));
        else
            diff.show(git -> git.getCommitFileDiff(id, file.path()));
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
