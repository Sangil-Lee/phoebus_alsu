package org.phoebus.applications.git;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.phoebus.applications.git.GitRepository.BranchInfo;
import org.phoebus.applications.git.GitRepository.RemoteBranch;
import org.phoebus.applications.git.GitRepository.TagInfo;
import org.phoebus.framework.jobs.JobManager;
import org.phoebus.ui.dialog.DialogHelper;
import org.phoebus.ui.dialog.ExceptionDetailsErrorDialog;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.Alert;
import javafx.scene.control.Alert.AlertType;
import javafx.scene.control.ButtonBar.ButtonData;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuItem;
import javafx.scene.layout.VBox;

/** Operations on versions of files that several views offer:
 *  Compare, restore, open an old version, merge conflicts
 */
@SuppressWarnings("nls")
class GitActions
{
    /** Operation on the repository */
    @FunctionalInterface
    interface Operation
    {
        void run(GitRepository git) throws Exception;
    }

    /** Runs an operation in the background, then refreshes what's shown */
    @FunctionalInterface
    interface Runner
    {
        void run(String name, Operation operation);
    }

    /** Button for restoring only the work tree */
    static final ButtonType WORK_TREE_ONLY = new ButtonType(Messages.RestoreWorkTree, ButtonData.OK_DONE);

    /** Button for restoring work tree and index */
    static final ButtonType WORK_TREE_AND_INDEX = new ButtonType(Messages.RestoreWorkTreeAndIndex, ButtonData.YES);

    /** Directories with exported old versions, deleted on exit */
    private static final List<Path> exported = new ArrayList<>();

    static
    {
        Runtime.getRuntime().addShutdownHook(new Thread(GitActions::deleteExported, "Delete exported git versions"));
    }

    private final Supplier<GitRepository> repo;
    private final Node owner;
    private final Runner runner;

    /** @param repo Provides the repository
     *  @param owner Node used to position dialogs
     *  @param runner Runs operations and refreshes, <code>null</code> to simply run them
     */
    GitActions(final Supplier<GitRepository> repo, final Node owner, final Runner runner)
    {
        this.repo = repo;
        this.owner = owner;
        this.runner = runner != null ? runner : this::runInBackground;
    }

    /** @return Repository, may be <code>null</code> */
    GitRepository getRepository()
    {
        return repo.get();
    }

    private void runInBackground(final String name, final Operation operation)
    {
        final GitRepository git = repo.get();
        if (git == null)
            return;
        JobManager.schedule(name, monitor ->
        {
            try
            {
                operation.run(git);
            }
            catch (Exception ex)
            {
                showError(name, ex);
            }
        });
    }

    /** @param name Operation
     *  @param operation Operation that's run with the repository
     */
    void run(final String name, final Operation operation)
    {
        runner.run(name, operation);
    }

    /** @param operation Operation that failed
     *  @param ex Error
     */
    void showError(final String operation, final Exception ex)
    {
        GitApp.logger.log(Level.WARNING, "Git " + operation + " failed", ex);
        ExceptionDetailsErrorDialog.openError(owner, Messages.ErrorTitle, operation, ex);
    }

    /** @param paths Supplies the selected files
     *  @return Menu to compare the work tree files with HEAD, index, other commit
     */
    Menu createCompareMenu(final Supplier<List<String>> paths)
    {
        final MenuItem head = new MenuItem(Messages.HEAD);
        head.setOnAction(event -> compareRef("HEAD", paths.get()));
        final MenuItem index = new MenuItem(Messages.Index);
        index.setOnAction(event -> compare(Revision.INDEX, Revision.WORK_TREE, paths.get()));
        final MenuItem other = new MenuItem(Messages.SelectRevision);
        other.setOnAction(event ->
        {
            final List<String> selected = paths.get();
            pickRevision(Messages.CompareWith, commit -> compare(commit, Revision.WORK_TREE, selected));
        });
        return new Menu(Messages.CompareWith, null, head, index, other);
    }

    /** @param paths Supplies the selected files
     *  @return Menu to replace the work tree files with their version in HEAD, index, other commit
     */
    Menu createRestoreMenu(final Supplier<List<String>> paths)
    {
        final MenuItem head = new MenuItem(Messages.HEAD);
        head.setOnAction(event ->
        {
            final List<String> selected = paths.get();
            resolve("HEAD", commit -> restore(selected, null, commit));
        });
        final MenuItem index = new MenuItem(Messages.Index);
        index.setOnAction(event -> restore(paths.get(), null, Revision.INDEX));
        final MenuItem other = new MenuItem(Messages.SelectRevision);
        other.setOnAction(event ->
        {
            final List<String> selected = paths.get();
            pickRevision(Messages.ReplaceWith, commit -> restore(selected, null, commit));
        });
        return new Menu(Messages.ReplaceWith, null, head, index, other);
    }

    /** Resolve a ref in the background
     *  @param ref Branch, tag, ...
     *  @param handler Called on UI thread with the commit
     */
    void resolve(final String ref, final Consumer<Revision.Commit> handler)
    {
        final GitRepository git = repo.get();
        if (git == null)
            return;
        JobManager.schedule(Messages.Compare, monitor ->
        {
            try
            {
                final Revision.Commit commit = git.resolve(ref);
                Platform.runLater(() -> handler.accept(commit));
            }
            catch (Exception ex)
            {
                showError(Messages.Compare, ex);
            }
        });
    }

    /** Compare a branch, tag, commit with the work tree
     *  @param ref Branch, tag, ...
     *  @param paths Files to compare, <code>null</code> for all
     */
    void compareRef(final String ref, final Collection<String> paths)
    {
        resolve(ref, commit -> compare(commit, Revision.WORK_TREE, paths));
    }

    /** Compare two branches, tags, commits
     *  @param a Old side
     *  @param b New side
     */
    void compareRefs(final String a, final String b)
    {
        resolve(a, from -> resolve(b, to -> compare(from, to, null)));
    }

    /** Open a tab that compares two revisions
     *  @param a Old side
     *  @param b New side
     *  @param paths Files to compare, <code>null</code> for all
     */
    void compare(final Revision a, final Revision b, final Collection<String> paths)
    {
        new CompareView(this, a, b, paths);
    }

    /** Replace files with their version in another revision, after asking
     *  @param paths Files in the work tree
     *  @param source Path of the single file in that revision if renamed, or <code>null</code>
     *  @param revision Commit or index
     */
    void restore(final List<String> paths, final String source, final Revision revision)
    {
        final GitRepository git = repo.get();
        if (git == null  ||  paths.isEmpty())
            return;
        final StringBuilder text = new StringBuilder(Messages.RestoreConfirm.formatted(revision));
        text.append("\n\n").append(String.join("\n", paths));
        // Warn about open files with unsaved changes, they would be overwritten
        final List<String> dirty = OpenFiles.list(git.getWorkTree())
                                            .stream()
                                            .filter(open -> open.item().isDirty())
                                            .map(open -> open.file().getAbsolutePath())
                                            .filter(file -> paths.stream().anyMatch(path -> file.equals(new File(git.getWorkTree(), path).getAbsolutePath())))
                                            .toList();
        if (! dirty.isEmpty())
            text.append("\n\n").append(Messages.RestoreDirty).append("\n").append(String.join("\n", dirty));

        final Alert dialog = new Alert(AlertType.CONFIRMATION, text.toString());
        dialog.setTitle(Messages.ReplaceWith);
        dialog.setHeaderText(null);
        dialog.setResizable(true);
        if (revision instanceof Revision.Commit)
            dialog.getButtonTypes().setAll(WORK_TREE_ONLY, WORK_TREE_AND_INDEX, ButtonType.CANCEL);
        else
            dialog.getButtonTypes().setAll(WORK_TREE_ONLY, ButtonType.CANCEL);
        DialogHelper.positionDialog(dialog, owner, -300, -150);
        final Optional<ButtonType> result = dialog.showAndWait();
        if (result.isEmpty()  ||  result.get() == ButtonType.CANCEL)
            return;
        final boolean index_too = result.get() == WORK_TREE_AND_INDEX;
        runner.run(Messages.ReplaceWith, repository ->
        {
            for (String path : paths)
                repository.restore(revision, paths.size() == 1  &&  source != null ? source : path, path, index_too);
        });
    }

    /** Open an old version of a file
     *
     *  <p>Writes the files of the file's directory in that version to a temporary directory,
     *  so that displays find the files that they reference by relative path,
     *  then opens the file with its default application.
     *
     *  @param path Path of the file in that commit
     *  @param commit Commit
     */
    void openVersion(final String path, final Revision.Commit commit)
    {
        final GitRepository git = repo.get();
        if (git == null)
            return;
        JobManager.schedule(Messages.OpenThisVersion, monitor ->
        {
            try
            {
                final File file = exportVersion(git, path, commit);
                Platform.runLater(() -> OpenFiles.open(file, false));
            }
            catch (Exception ex)
            {
                showError(Messages.OpenThisVersion, ex);
            }
        });
    }

    /** @param git Repository
     *  @param path Path of the file in that commit
     *  @param commit Commit
     *  @return Exported file, read-only, in a temporary directory together with the other files of its directory
     */
    static File exportVersion(final GitRepository git, final String path, final Revision.Commit commit) throws Exception
    {
        final Path dir = Files.createTempDirectory("git-" + commit.id().substring(0, 7) + "-");
        synchronized (exported)
        {
            exported.add(dir);
        }
        final int sep = path.lastIndexOf('/');
        git.export(commit, sep < 0 ? "" : path.substring(0, sep), dir.toFile());
        final File file = new File(dir.toFile(), path);
        if (! file.isFile())
            throw new Exception(path + " " + Messages.NotInRevision + " " + commit);
        // Old versions are not meant to be edited
        try (Stream<Path> files = Files.walk(dir))
        {
            files.filter(Files::isRegularFile).forEach(f -> f.toFile().setWritable(false));
        }
        return file;
    }

    /** Delete directories of exported versions */
    static void deleteExported()
    {
        synchronized (exported)
        {
            for (Path dir : exported)
                try (Stream<Path> files = Files.walk(dir))
                {
                    for (Path file : files.sorted(Comparator.reverseOrder()).toList())
                        Files.deleteIfExists(file);
                }
                catch (IOException ex)
                {
                    // Ignore, temporary directory
                }
            exported.clear();
        }
    }

    /** Prompt for a branch, tag or commit
     *  @param title Dialog title
     *  @param handler Called on UI thread with the selected commit
     */
    void pickRevision(final String title, final Consumer<Revision.Commit> handler)
    {
        final GitRepository git = repo.get();
        if (git == null)
            return;
        JobManager.schedule(title, monitor ->
        {
            try
            {
                final List<String> refs = new ArrayList<>();
                for (BranchInfo branch : git.getBranches())
                    refs.add(branch.name());
                for (List<RemoteBranch> branches : git.getRemoteBranches().values())
                    for (RemoteBranch branch : branches)
                        refs.add(branch.remote() + "/" + branch.name());
                for (TagInfo tag : git.getTags())
                    refs.add(tag.name());
                Platform.runLater(() ->
                {
                    final String ref = promptRevision(title, refs);
                    if (ref != null)
                        resolve(ref, handler);
                });
            }
            catch (Exception ex)
            {
                showError(title, ex);
            }
        });
    }

    /** @param title Dialog title
     *  @param refs Branches and tags to offer
     *  @return Entered branch, tag, commit id, <code>null</code> if cancelled
     */
    private String promptRevision(final String title, final List<String> refs)
    {
        final ComboBox<String> revision = new ComboBox<>();
        revision.setEditable(true);
        revision.getItems().setAll(refs);
        revision.setPrefWidth(400);
        final Dialog<String> dialog = new Dialog<>();
        dialog.setTitle(title);
        dialog.setHeaderText(Messages.SelectRevisionInfo);
        final VBox content = new VBox(5, new Label(Messages.Revision), revision);
        content.setPadding(new Insets(10));
        dialog.getDialogPane().setContent(content);
        dialog.getDialogPane().getButtonTypes().setAll(ButtonType.OK, ButtonType.CANCEL);
        dialog.getDialogPane().lookupButton(ButtonType.OK).disableProperty()
              .bind(revision.getEditor().textProperty().isEmpty());
        dialog.setResultConverter(button -> button == ButtonType.OK ? revision.getEditor().getText().trim() : null);
        DialogHelper.positionDialog(dialog, owner, -200, -100);
        Platform.runLater(revision.getEditor()::requestFocus);
        return dialog.showAndWait().orElse(null);
    }

    /** Open the merge tool for a conflict
     *  @param path File with conflict
     */
    void openMergeTool(final String path)
    {
        new MergeView(this, path);
    }

    /** @param paths Paths
     *  @return Paths as one line each
     */
    static String list(final Collection<String> paths)
    {
        return paths.stream().collect(Collectors.joining("\n"));
    }
}
