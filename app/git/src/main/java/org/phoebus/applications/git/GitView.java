package org.phoebus.applications.git;

import java.io.File;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.stream.Collectors;

import org.eclipse.jgit.api.errors.NotMergedException;
import org.eclipse.jgit.lib.BatchingProgressMonitor;
import org.eclipse.jgit.lib.Constants;
import org.phoebus.applications.git.GitRepository.BranchInfo;
import org.phoebus.applications.git.GitRepository.ChangeType;
import org.phoebus.applications.git.GitRepository.CommitFile;
import org.phoebus.applications.git.GitRepository.CommitInfo;
import org.phoebus.applications.git.GitRepository.FileStatus;
import org.phoebus.applications.git.GitRepository.PushOptions;
import org.phoebus.applications.git.GitRepository.RemoteBranch;
import org.phoebus.applications.git.GitRepository.ResetMode;
import org.phoebus.applications.git.GitRepository.State;
import org.phoebus.applications.git.GitRepository.StatusEntry;
import org.phoebus.applications.git.GitRepository.StashInfo;
import org.phoebus.applications.git.GitRepository.TagInfo;
import org.phoebus.framework.jobs.JobManager;
import org.phoebus.logbook.LogEntry;
import org.phoebus.logbook.olog.ui.write.LogEntryEditorStage;
import org.phoebus.framework.jobs.JobRunnable;
import org.phoebus.framework.persistence.Memento;
import org.phoebus.framework.workbench.ApplicationService;
import org.phoebus.framework.spi.AppDescriptor;
import org.phoebus.framework.spi.AppInstance;
import org.phoebus.ui.dialog.DialogHelper;
import org.phoebus.ui.dialog.ExceptionDetailsErrorDialog;
import org.phoebus.ui.docking.DockItem;
import org.phoebus.ui.docking.DockPane;
import org.phoebus.ui.javafx.ImageCache;

import javafx.application.Platform;
import javafx.beans.value.ChangeListener;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Alert;
import javafx.scene.control.Alert.AlertType;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ChoiceDialog;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.MenuButton;
import javafx.scene.control.MenuItem;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.Separator;
import javafx.scene.control.SplitPane;
import javafx.scene.control.TextField;
import javafx.scene.control.TextInputDialog;
import javafx.scene.control.ToolBar;
import javafx.scene.image.ImageView;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.DirectoryChooser;
import javafx.stage.Window;

/** Dock tab for one repository
 *
 *  <p>Tool bar with remote and branch operations,
 *  side bar with branches, tags, remotes, stashes,
 *  and either the file status, history or a stash in the center.
 */
@SuppressWarnings("nls")
public class GitView implements AppInstance
{
    /** Memento tags */
    private static final String REPOSITORY = "repository",
                                ALL_BRANCHES = "all_branches";

    private final AppDescriptor app;

    /** Center area, shows file status, history or stash */
    private final BorderPane layout = new BorderPane();
    private final TextField repo_path = new TextField();
    private final HBox repo_bar = new HBox(5);
    private final Button pull = new Button(Messages.Pull);
    private final Button push = new Button(Messages.Push);
    private final GitSidebar sidebar;
    private final FileStatusPane file_status;
    private final HistoryPane history;
    private final DiffPane stash_diff;

    /** Currently open repository, accessed from UI thread and jobs */
    private volatile GitRepository repo;

    /** Show history of all branches, or only the current one? */
    private volatile boolean all_branches = true;

    /** Number of commits to read, increased by 'load more' */
    private volatile int log_count = GitApp.log_count;

    /** Text to search in the history, empty to show all */
    private volatile String search = "";

    /** Recently opened repositories */
    private final MenuButton recent = new MenuButton(Messages.Recent);

    /** Files open in phoebus tabs, to show their history */
    private final MenuButton open_files = new MenuButton(Messages.OpenFiles);

    /** Open Git views, accessed on UI thread */
    private static final List<GitView> views = new ArrayList<>();

    /** File to select in the file status once the repository has been read, or <code>null</code> */
    private volatile String pending_select = null;

    /** Shown while operations are running */
    private final ProgressIndicator busy = new ProgressIndicator();
    private final Label busy_label = new Label();

    /** Number of running operations */
    private final AtomicInteger active = new AtomicInteger();

    /** Time in millis when the last operation ended */
    private volatile long last_activity = 0;

    /** Changes within this time after an operation are caused by that operation */
    private static final long OWN_CHANGES_MS = 1500;

    /** Watches the repository for changes, <code>null</code> if not watching */
    private RepositoryWatcher watcher = null;

    private final DockItem tab;

    /** Window that's checked for focus, to refresh when focused */
    private Window focus_window = null;
    private final ChangeListener<Boolean> focus_listener = (p, o, focused) -> focusChanged(focused);


    /** Most recent state, used to list branches for merge. Only accessed on UI thread */
    private State last_state = null;

    GitView(final AppDescriptor app, final File directory)
    {
        this.app = app;

        file_status = new FileStatusPane(() -> repo, ex -> showError(Messages.Diff, ex), new FileStatusActions());
        history = new HistoryPane(() -> repo, ex -> showError(Messages.Diff, ex), all_branches, new HistoryActions());
        stash_diff = new DiffPane(() -> repo, ex -> showError(Messages.Diff, ex));
        sidebar = new GitSidebar(new SidebarActions());

        final SplitPane split = new SplitPane(sidebar, layout);
        split.setDividerPositions(0.2);
        SplitPane.setResizableWithParent(sidebar, false);

        final BorderPane content = new BorderPane(split);
        // Tool bar with operations, below that the repository location
        content.setTop(new VBox(createToolbar(), createRepositoryBar()));

        tab = new DockItem(this, content);
        DockPane.getActiveDockPane().addTab(tab);
        tab.addClosedNotification(this::dispose);
        views.add(this);

        // Refresh when tab is selected, or its window gets the focus
        tab.selectedProperty().addListener((p, o, selected) ->
        {
            if (selected  &&  GitApp.auto_refresh)
                refresh();
        });
        content.sceneProperty().addListener((p, o, scene) ->
        {
            if (scene != null)
            {
                scene.windowProperty().addListener((wp, wo, window) -> watchFocus(window));
                watchFocus(scene.getWindow());
            }
        });

        if (directory != null)
            setRepository(directory);
    }

    private ToolBar createToolbar()
    {
        final Button fetch = new Button(Messages.Fetch);
        fetch.setOnAction(event -> runAndRefresh(Messages.Fetch, git -> git.fetch(new GitCredentialsProvider(layout))));

        pull.setOnAction(event -> pull());
        push.setOnAction(event -> push());

        final Button branch = new Button(Messages.Branch);
        branch.setOnAction(event -> createBranch(null));

        final Button merge = new Button(Messages.Merge);
        merge.setOnAction(event -> merge(null));

        final Button tag = new Button(Messages.Tag);
        tag.setOnAction(event -> createTag(null));

        final Button stash = new Button(Messages.Stash);
        stash.setOnAction(event -> stash());

        final Button refresh = new Button(Messages.Refresh,
                                          new ImageView(ImageCache.getImage(DockPane.class, "/icons/refresh.png")));
        refresh.setOnAction(event -> refresh());

        repo_path.setPromptText(Messages.NoRepository);
        repo_path.setOnAction(event -> setRepository(new File(repo_path.getText().trim())));
        repo_path.setPrefColumnCount(40);
        final Button browse = new Button(Messages.Browse);
        browse.setOnAction(event -> browse());
        final Button clone = new Button(Messages.Clone);
        clone.setOnAction(event -> cloneRepository());
        final Button create = new Button(Messages.NewRepository);
        create.setOnAction(event -> createRepository());
        updateRecentMenu();
        open_files.setOnShowing(event -> updateOpenFilesMenu());
        // Menu needs at least one item to show
        open_files.getItems().add(new MenuItem());
        repo_bar.getChildren().setAll(new Label(Messages.Repository), repo_path, recent, browse, clone, create, open_files);
        repo_bar.setAlignment(Pos.CENTER_LEFT);
        repo_bar.setPadding(new Insets(2, 5, 4, 5));
        HBox.setHgrow(repo_path, Priority.ALWAYS);

        busy.setPrefSize(18, 18);
        busy.setVisible(false);
        busy_label.setVisible(false);

        return new ToolBar(fetch, pull, push, new Separator(), branch, merge, tag, stash, new Separator(), refresh,
                           busy, busy_label);
    }

    /** @return Bar with repository location, kept separate from the tool bar so it is never in the overflow menu */
    private HBox createRepositoryBar()
    {
        return repo_bar;
    }

    /** @param focused Did the window of this tab get the focus? */
    private void focusChanged(final boolean focused)
    {
        if (focused  &&  tab.isSelected()  &&  GitApp.auto_refresh)
            refresh();
    }

    /** @param window Window to check for focus, replacing the previous one */
    private void watchFocus(final Window window)
    {
        if (focus_window == window)
            return;
        if (focus_window != null)
            focus_window.focusedProperty().removeListener(focus_listener);
        focus_window = window;
        if (focus_window != null)
            focus_window.focusedProperty().addListener(focus_listener);
    }

    /** Refresh after the repository changed, unless caused by our own operations */
    private void autoRefresh()
    {
        if (active.get() == 0  &&  System.currentTimeMillis() - last_activity > OWN_CHANGES_MS)
            refresh();
    }

    /** Watch current repository for changes */
    private synchronized void startWatcher()
    {
        stopWatcher();
        final GitRepository current = repo;
        if (current == null  ||  ! GitApp.auto_refresh)
            return;
        try
        {
            watcher = new RepositoryWatcher(() -> current.getWatchDirectories(RepositoryWatcher.MAX_DIRECTORIES),
                                            this::autoRefresh);
        }
        catch (Exception ex)
        {
            GitApp.logger.log(Level.WARNING, "Cannot watch " + current.getWorkTree(), ex);
        }
    }

    private synchronized void stopWatcher()
    {
        if (watcher != null)
        {
            watcher.close();
            watcher = null;
        }
    }

    /** Run operation in background while showing the busy indicator
     *
     *  <p>Repository calls are serialized by {@link GitRepository},
     *  so operations queue up instead of failing on a locked index.
     *
     *  @param name Operation name, shown next to the busy indicator
     *  @param runnable Operation
     */
    private void schedule(final String name, final JobRunnable runnable)
    {
        updateBusy(active.incrementAndGet(), name);
        JobManager.schedule(name, monitor ->
        {
            try
            {
                runnable.run(monitor);
            }
            finally
            {
                last_activity = System.currentTimeMillis();
                updateBusy(active.decrementAndGet(), null);
            }
        });
    }

    /** @param count Number of running operations
     *  @param name Name of the operation that was just started, or <code>null</code>
     */
    private void updateBusy(final int count, final String name)
    {
        Platform.runLater(() ->
        {
            busy.setVisible(count > 0);
            busy_label.setVisible(count > 0);
            if (name != null)
                busy_label.setText(name + "...");
        });
    }

    /** Handle file status actions */
    private class FileStatusActions implements FileStatusPane.Actions
    {
        @Override
        public void stage(final List<String> paths)
        {
            if (! paths.isEmpty())
                runAndRefresh(Messages.Stage, git -> git.stage(paths));
        }

        @Override
        public void unstage(final List<String> paths)
        {
            if (! paths.isEmpty())
                runAndRefresh(Messages.Unstage, git -> git.unstage(paths));
        }

        @Override
        public void discard(final List<StatusEntry> entries)
        {
            if (entries.isEmpty())
                return;
            final String files = entries.stream()
                                        .map(e -> e.path() + " (" + e.type() + ")")
                                        .collect(Collectors.joining("\n"));
            if (confirm(Messages.Discard, Messages.DiscardConfirm + "\n\n" + files))
                runAndRefresh(Messages.Discard, git -> git.discard(entries.stream().map(StatusEntry::path).toList()));
        }

        @Override
        public void commit(final String message, final boolean amend, final boolean push)
        {
            final GitRepository current = repo;
            if (current == null)
                return;
            if (! GitApp.check_before_commit)
            {
                doCommit(message, amend, push);
                return;
            }
            schedule(Messages.CommitCheck, monitor ->
            {
                try
                {
                    final List<CommitCheck.Problem> problems = CommitCheck.check(current);
                    Platform.runLater(() ->
                    {
                        if (problems.isEmpty()  ||  confirmProblems(problems))
                            doCommit(message, amend, push);
                    });
                }
                catch (Exception ex)
                {
                    showError(Messages.CommitCheck, ex);
                }
            });
        }

        /** Commit, then optionally push */
        private void doCommit(final String message, final boolean amend, final boolean push)
        {
            runAndRefresh(Messages.Commit, git ->
            {
                git.commit(message, amend);
                Platform.runLater(file_status::clearMessage);
                if (push)
                    pushAndOfferLogbook(git, PushOptions.DEFAULT);
            });
        }

        @Override
        public void stageHunk(final String path, final int hunk)
        {
            runAndRefresh(Messages.StageHunk, git -> git.stageHunk(path, hunk));
        }

        @Override
        public void unstageHunk(final String path, final int hunk)
        {
            runAndRefresh(Messages.UnstageHunk, git -> git.unstageHunk(path, hunk));
        }

        @Override
        public void discardHunk(final String path, final int hunk)
        {
            if (confirm(Messages.DiscardHunk, Messages.DiscardHunkConfirm + "\n" + path))
                runAndRefresh(Messages.DiscardHunk, git -> git.discardHunk(path, hunk));
        }

        @Override
        public void resolveConflict(final List<String> paths, final boolean ours)
        {
            runAndRefresh(ours ? Messages.ResolveOurs : Messages.ResolveTheirs, git ->
            {
                for (String path : paths)
                    git.resolveConflict(path, ours);
            });
        }

        @Override
        public void ignore(final String path)
        {
            GitView.this.ignore(path);
        }

        @Override
        public void continueRebase()
        {
            runMayConflict(Messages.ContinueRebase, GitRepository::continueRebase);
        }

        @Override
        public void skipRebase()
        {
            runMayConflict(Messages.SkipRebase, GitRepository::skipRebase);
        }

        @Override
        public void openFile(final String path, final boolean editor)
        {
            GitView.this.openFile(path, editor);
        }

        @Override
        public void abortRebase()
        {
            if (confirm(Messages.AbortRebase, Messages.AbortRebaseConfirm))
                runAndRefresh(Messages.AbortRebase, GitRepository::abortRebase);
        }

        @Override
        public void getHeadMessage(final Consumer<String> handler)
        {
            final GitRepository current = repo;
            if (current == null)
                return;
            schedule(Messages.Amend, monitor ->
            {
                try
                {
                    final String message = current.getHeadMessage();
                    Platform.runLater(() -> handler.accept(message));
                }
                catch (Exception ex)
                {
                    showError(Messages.Amend, ex);
                }
            });
        }

        @Override
        public void showFileHistory(final String path)
        {
            GitView.this.showFileHistory(path);
        }

        @Override
        public void abortMerge()
        {
            if (confirm(Messages.AbortMerge, Messages.AbortMergeConfirm))
                runAndRefresh(Messages.AbortMerge, GitRepository::abortMerge);
        }
    }

    /** Handle history actions */
    private class HistoryActions implements HistoryPane.Actions
    {
        @Override
        public void showAllBranches(final boolean all)
        {
            all_branches = all;
            refresh();
        }

        @Override
        public void createTag(final String commit)
        {
            GitView.this.createTag(commit);
        }

        @Override
        public void createBranch(final String commit)
        {
            GitView.this.createBranch(commit);
        }

        @Override
        public void cherryPick(final CommitInfo commit)
        {
            if (confirm(Messages.CherryPick, Messages.CherryPick + ": " + commit.message()))
                runMayConflict(Messages.CherryPick, git -> git.cherryPick(commit.id()));
        }

        @Override
        public void revert(final CommitInfo commit)
        {
            if (confirm(Messages.Revert, Messages.Revert + ": " + commit.message()))
                runMayConflict(Messages.Revert, git -> git.revert(commit.id()));
        }

        @Override
        public void logbook(final CommitInfo commit)
        {
            openLogbook(List.of(commit.id()), null);
        }

        @Override
        public void reset(final CommitInfo commit)
        {
            GitView.this.reset(commit);
        }

        @Override
        public void loadMore()
        {
            log_count += GitApp.log_count;
            refresh();
        }

        @Override
        public void showFileStatus()
        {
            sidebar.selectFileStatus();
        }

        @Override
        public void showFileHistory(final String path)
        {
            GitView.this.showFileHistory(path);
        }

        @Override
        public void openFile(final String path, final boolean editor)
        {
            GitView.this.openFile(path, editor);
        }

        @Override
        public void search(final String text)
        {
            search = text;
            refresh();
        }
    }

    /** Push, then offer a logbook entry for the pushed commits
     *
     *  <p>Called on a background thread.
     *
     *  @param git Repository
     *  @param options Push options
     */
    private void pushAndOfferLogbook(final GitRepository git, final PushOptions options) throws Exception
    {
        // Commits that the push sends, determined before the push
        final List<String> commits = git.getUnpushedCommits(options);
        final String remote = git.getPushTarget(options).remote();
        git.push(options, new GitCredentialsProvider(layout));
        if (GitApp.logbook_after_push  &&  ! commits.isEmpty())
            Platform.runLater(() ->
            {
                if (confirm(Messages.Logbook, Messages.LogbookAfterPush + "\n\n" + String.format(Messages.Commits, commits.size())))
                    openLogbook(commits, remote);
            });
    }

    /** Open logbook editor with an entry for commits
     *  @param commits Commit ids, newest first
     *  @param remote Remote that the commits were pushed to, or <code>null</code>
     */
    private void openLogbook(final List<String> commits, final String remote)
    {
        final GitRepository current = repo;
        if (current == null)
            return;
        schedule(Messages.Logbook, monitor ->
        {
            try
            {
                final LogEntry entry = GitLogbook.createEntry(current, commits, remote);
                Platform.runLater(() -> new LogEntryEditorStage(entry).show());
            }
            catch (Exception ex)
            {
                showError(Messages.Logbook, ex);
            }
        });
    }

    /** Show problems found before a commit
     *  @param problems Problems
     *  @return <code>true</code> to commit anyway
     */
    private boolean confirmProblems(final List<CommitCheck.Problem> problems)
    {
        final ListView<CommitCheck.Problem> list = new ListView<>();
        list.getItems().setAll(problems);
        list.setPrefSize(700, 250);
        list.setCellFactory(view -> new ListCell<>()
        {
            @Override
            protected void updateItem(final CommitCheck.Problem problem, final boolean empty)
            {
                super.updateItem(problem, empty);
                if (empty  ||  problem == null)
                {
                    setText(null);
                    setStyle(null);
                    return;
                }
                final boolean error = problem.severity() == CommitCheck.Severity.ERROR;
                setText((error ? Messages.Error : Messages.Warning) + "   " + problem.path() + "   " + problem.message());
                setStyle(error ? "-fx-text-fill: #c62828;" : "-fx-text-fill: #e65100;");
            }
        });
        final Label info = new Label(Messages.CommitCheckInfo);
        info.setWrapText(true);
        final VBox content = new VBox(5, info, list);

        final ButtonType commit_anyway = new ButtonType(Messages.CommitAnyway, ButtonBar.ButtonData.OK_DONE);
        final Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle(Messages.CommitCheck);
        dialog.setHeaderText(null);
        dialog.getDialogPane().setContent(content);
        dialog.getDialogPane().getButtonTypes().addAll(commit_anyway, ButtonType.CANCEL);
        dialog.setResizable(true);
        DialogHelper.positionDialog(dialog, layout, -350, -200);
        return dialog.showAndWait().orElse(ButtonType.CANCEL) == commit_anyway;
    }

    /** @param path File to show in a new 'file history' tab */
    private void showFileHistory(final String path)
    {
        new FileHistoryView(() -> repo, path, ex -> showError(Messages.FileHistory, ex));
    }

    /** @param path File in the work tree
     *  @param editor Open display in editor, otherwise default application
     */
    private void openFile(final String path, final boolean editor)
    {
        final GitRepository current = repo;
        if (current == null)
            return;
        final File file = new File(current.getWorkTree(), path);
        if (file.isFile())
            OpenFiles.open(file, editor);
        else
            showError(Messages.Open, new Exception(file + " does not exist"));
    }

    /** @return Work tree of this view's repository, <code>null</code> if none */
    private File getWorkTree()
    {
        final GitRepository current = repo;
        return current == null ? null : current.getWorkTree();
    }

    /** @param file File
     *  @return Path of the file relative to the work tree, using '/'
     */
    private static String relative(final File work_tree, final File file)
    {
        return work_tree.getAbsoluteFile().toPath().relativize(file.getAbsoluteFile().toPath())
                        .toString().replace(File.separatorChar, '/');
    }

    /** @param file File
     *  @return Open Git view whose work tree contains the file, or <code>null</code>
     */
    private static GitView findView(final File file)
    {
        final String path = file.getAbsolutePath();
        for (GitView view : views)
        {
            final File work_tree = view.getWorkTree();
            if (work_tree != null  &&  (path + File.separator).startsWith(work_tree.getAbsolutePath() + File.separator))
                return view;
        }
        return null;
    }

    /** Show the history of a file, from any repository
     *
     *  <p>Uses the repository of an open Git view, otherwise opens the repository for the history tab.
     *
     *  @param file File inside a git work tree
     */
    static void showFileHistory(final File file)
    {
        final GitView view = findView(file);
        if (view != null)
        {
            view.showFileHistory(relative(view.getWorkTree(), file));
            return;
        }
        try
        {
            final GitRepository owned = new GitRepository(file.isDirectory() ? file : file.getParentFile());
            new FileHistoryView(() -> owned, relative(owned.getWorkTree(), file),
                                ex -> ExceptionDetailsErrorDialog.openError(Messages.FileHistory, ex), owned);
        }
        catch (Exception ex)
        {
            ExceptionDetailsErrorDialog.openError(Messages.FileHistory, Messages.NotInRepository + "\n" + file, ex);
        }
    }

    /** Show a file in a Git view: Select its tab or open a new one, and select the file if it changed
     *  @param file File or directory inside a git work tree
     */
    static void showInGit(final File file)
    {
        GitView view = findView(file);
        if (view != null)
        {
            view.tab.select();
            view.pending_select = file.isFile() ? relative(view.getWorkTree(), file) : null;
            view.sidebar.selectFileStatus();
            view.refresh();
            return;
        }
        if (! isInRepository(file))
        {
            ExceptionDetailsErrorDialog.openError(Messages.ShowInGitMenu, Messages.NotInRepository + "\n" + file, null);
            return;
        }
        final AppDescriptor app = ApplicationService.findApplication(GitApp.Name);
        view = new GitView(app, file.isDirectory() ? file : file.getParentFile());
        // Path relative to the work tree is known once the repository has been opened
        view.pending_absolute = file.isFile() ? file : null;
    }

    /** File to select once the repository is open, path not yet known relative to work tree */
    private volatile File pending_absolute = null;

    /** @return Is the file inside a git work tree? */
    private static boolean isInRepository(final File file)
    {
        for (File dir = file.isDirectory() ? file : file.getParentFile(); dir != null; dir = dir.getParentFile())
            if (new File(dir, Constants.DOT_GIT).exists())
                return true;
        return false;
    }

    /** Update menu of files that are open in phoebus tabs */
    private void updateOpenFilesMenu()
    {
        open_files.getItems().clear();
        for (OpenFiles.OpenFile open : OpenFiles.list())
        {
            final File file = open.file();
            final MenuItem item = new MenuItem(file.getName() + "   " + file.getParent());
            item.setOnAction(event -> showFileHistory(file));
            item.setDisable(! isInRepository(file));
            open_files.getItems().add(item);
        }
        if (open_files.getItems().isEmpty())
        {
            final MenuItem none = new MenuItem(Messages.NoOpenFiles);
            none.setDisable(true);
            open_files.getItems().add(none);
        }
    }

    /** Handle side bar actions */
    private class SidebarActions implements GitSidebar.Actions
    {
        @Override
        public void showFileStatus()
        {
            layout.setCenter(file_status);
        }

        @Override
        public void showHistory(final String commit)
        {
            layout.setCenter(history);
            if (commit != null)
                history.showCommit(commit);
        }

        @Override
        public void showStash(final StashInfo stash)
        {
            layout.setCenter(stash_diff);
            stash_diff.show(git -> git.getStashDiff(stash.commit()));
        }

        @Override
        public void checkout(final BranchInfo branch)
        {
            runAndRefresh(Messages.Checkout, git -> git.checkout(branch.name()));
        }

        @Override
        public void checkoutRemote(final RemoteBranch branch)
        {
            final TextInputDialog dialog = new TextInputDialog(branch.name());
            dialog.setTitle(Messages.CheckoutRemote);
            dialog.setHeaderText(branch.fullName());
            dialog.setContentText(Messages.BranchName);
            DialogHelper.positionDialog(dialog, layout, -200, -100);
            dialog.showAndWait()
                  .map(String::trim)
                  .filter(name -> ! name.isEmpty())
                  .ifPresent(name -> runAndRefresh(Messages.Checkout, git -> git.checkoutRemote(branch, name)));
        }

        @Override
        public void createBranch(final String start_point)
        {
            GitView.this.createBranch(start_point);
        }

        @Override
        public void deleteBranch(final BranchInfo branch)
        {
            if (! confirm(Messages.DeleteBranch, Messages.DeleteBranchConfirm + " " + branch.name()))
                return;
            runAndRefresh(Messages.DeleteBranch, git ->
            {
                try
                {
                    git.deleteBranch(branch.name(), false);
                }
                catch (NotMergedException ex)
                {
                    // Ask on UI thread, then delete in a new job
                    Platform.runLater(() ->
                    {
                        if (confirm(Messages.DeleteBranch, Messages.NotMerged + " " + branch.name()))
                            runAndRefresh(Messages.DeleteBranch, g -> g.deleteBranch(branch.name(), true));
                    });
                }
            });
        }

        @Override
        public void applyStash(final StashInfo stash, final boolean drop)
        {
            runAndRefresh(drop ? Messages.PopStash : Messages.ApplyStash,
                          git -> git.applyStash(stash.index(), drop));
        }

        @Override
        public void dropStash(final StashInfo stash)
        {
            if (confirm(Messages.DropStash, Messages.DropStashConfirm + " " + stash.message()))
                runAndRefresh(Messages.DropStash, git -> git.dropStash(stash.index()));
        }

        @Override
        public void merge(final String branch)
        {
            GitView.this.merge(branch);
        }

        @Override
        public void rebaseOnto(final String branch)
        {
            if (last_state != null  &&
                confirm(Messages.RebaseOnto, Messages.RebaseConfirm + "\n" + last_state.branch() + " \u2192 " + branch))
                runMayConflict(Messages.RebaseOnto, git -> git.rebase(branch));
        }

        @Override
        public void renameBranch(final BranchInfo branch)
        {
            final TextInputDialog dialog = new TextInputDialog(branch.name());
            dialog.setTitle(Messages.RenameBranch);
            dialog.setHeaderText(branch.name());
            dialog.setContentText(Messages.BranchName);
            DialogHelper.positionDialog(dialog, layout, -200, -100);
            dialog.showAndWait()
                  .map(String::trim)
                  .filter(name -> ! name.isEmpty()  &&  ! name.equals(branch.name()))
                  .ifPresent(name -> runAndRefresh(Messages.RenameBranch, git -> git.renameBranch(branch.name(), name)));
        }

        @Override
        public void pushTag(final TagInfo tag)
        {
            runAndRefresh(Messages.PushTag, git -> git.pushTag(tag.name(), new GitCredentialsProvider(layout)));
        }

        @Override
        public void deleteTag(final TagInfo tag)
        {
            if (confirm(Messages.DeleteTag, Messages.DeleteTag + ": " + tag.name()))
                runAndRefresh(Messages.DeleteTag, git -> git.deleteTag(tag.name()));
        }

        @Override
        public void deleteRemoteTag(final TagInfo tag)
        {
            if (confirm(Messages.DeleteRemoteTag, Messages.DeleteRemoteTag + ": " + tag.name()))
                runAndRefresh(Messages.DeleteRemoteTag,
                              git -> git.deleteRemoteTag(tag.name(), new GitCredentialsProvider(layout)));
        }

        @Override
        public void addRemote()
        {
            final TextField name = new TextField(last_state != null  &&  last_state.remotes().isEmpty() ? "origin" : "");
            final TextField url = new TextField();
            url.setPrefColumnCount(40);
            url.setPromptText("https://host/group/repo.git, git@host:group/repo.git");
            if (promptRemote(Messages.AddRemote, name, url))
            {
                final String remote = name.getText().trim(), address = url.getText().trim();
                runAndRefresh(Messages.AddRemote, git -> git.addRemote(remote, address));
            }
        }

        @Override
        public void editRemote(final String name, final String url)
        {
            final TextField name_field = new TextField(name);
            name_field.setEditable(false);
            final TextField url_field = new TextField(url == null ? "" : url);
            url_field.setPrefColumnCount(40);
            if (promptRemote(Messages.EditRemote, name_field, url_field))
            {
                final String address = url_field.getText().trim();
                runAndRefresh(Messages.EditRemote, git -> git.setRemoteUrl(name, address));
            }
        }

        @Override
        public void removeRemote(final String name)
        {
            if (confirm(Messages.RemoveRemote, Messages.RemoveRemote + ": " + name))
                runAndRefresh(Messages.RemoveRemote, git -> git.removeRemote(name));
        }
    }

    /** Prompt for remote name and URL
     *  @return <code>true</code> if user confirmed with non-empty name and URL
     */
    private boolean promptRemote(final String title, final TextField name, final TextField url)
    {
        final GridPane grid = new GridPane();
        grid.setHgap(5);
        grid.setVgap(5);
        grid.addRow(0, new Label(Messages.RemoteName), name);
        grid.addRow(1, new Label(Messages.RemoteUrl), url);

        final Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle(title);
        dialog.setHeaderText(null);
        dialog.getDialogPane().setContent(grid);
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        DialogHelper.positionDialog(dialog, layout, -200, -100);
        Platform.runLater(() -> (name.isEditable() && name.getText().isEmpty() ? name : url).requestFocus());
        return dialog.showAndWait().orElse(ButtonType.CANCEL) == ButtonType.OK  &&
               ! name.getText().isBlank()  &&  ! url.getText().isBlank();
    }

    /** @return <code>true</code> if user confirmed */
    private boolean confirm(final String title, final String message)
    {
        final Alert alert = new Alert(AlertType.CONFIRMATION, message, ButtonType.OK, ButtonType.CANCEL);
        alert.setTitle(title);
        alert.setHeaderText(null);
        DialogHelper.positionDialog(alert, layout, -200, -100);
        return alert.showAndWait().orElse(ButtonType.CANCEL) == ButtonType.OK;
    }

    /** Prompt for name of new branch and create it
     *  @param start_point Commit id, branch or tag, <code>null</code> for HEAD
     */
    private void createBranch(final String start_point)
    {
        final TextField name = new TextField();
        name.setPrefColumnCount(25);
        final CheckBox checkout = new CheckBox(Messages.CheckoutNewBranch);
        checkout.setSelected(true);
        final GridPane grid = new GridPane();
        grid.setHgap(5);
        grid.setVgap(5);
        grid.addRow(0, new Label(Messages.BranchName), name);
        grid.add(checkout, 1, 1);

        final Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle(Messages.CreateBranch);
        dialog.setHeaderText(start_point == null ? "HEAD" : start_point);
        dialog.getDialogPane().setContent(grid);
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        DialogHelper.positionDialog(dialog, layout, -200, -100);
        Platform.runLater(name::requestFocus);

        final Optional<ButtonType> result = dialog.showAndWait();
        final String branch = name.getText().trim();
        if (result.orElse(ButtonType.CANCEL) == ButtonType.OK  &&  ! branch.isEmpty())
        {
            final boolean do_checkout = checkout.isSelected();
            runAndRefresh(Messages.CreateBranch, git -> git.createBranch(branch, start_point, do_checkout));
        }
    }

    /** Prompt for branch to merge into the current branch, and merge it
     *  @param suggestion Branch to suggest, <code>null</code> to let user pick
     */
    private void merge(final String suggestion)
    {
        if (last_state == null)
            return;
        // Offer local branches other than the current one, and remote branches
        final ComboBox<String> branch = new ComboBox<>();
        for (BranchInfo info : last_state.branches())
            if (! info.current())
                branch.getItems().add(info.name());
        last_state.remotes().values().forEach(list -> list.forEach(rb -> branch.getItems().add(rb.fullName())));
        if (suggestion != null)
            branch.setValue(suggestion);
        else if (! branch.getItems().isEmpty())
            branch.setValue(branch.getItems().get(0));
        final CheckBox no_ff = new CheckBox(Messages.NoFastForward);
        final GridPane grid = new GridPane();
        grid.setHgap(5);
        grid.setVgap(5);
        grid.addRow(0, new Label(Messages.MergeBranch), branch);
        grid.add(no_ff, 1, 1);

        final Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle(Messages.Merge);
        dialog.setHeaderText(Messages.MergeInto + " " + last_state.branch());
        dialog.getDialogPane().setContent(grid);
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        DialogHelper.positionDialog(dialog, layout, -200, -100);

        if (dialog.showAndWait().orElse(ButtonType.CANCEL) == ButtonType.OK  &&  branch.getValue() != null)
        {
            final String name = branch.getValue();
            final boolean no_fast_forward = no_ff.isSelected();
            runMayConflict(Messages.Merge, git -> git.merge(name, no_fast_forward));
        }
    }

    /** Prompt for tag name and message, then create the tag
     *  @param commit Commit to tag, <code>null</code> for HEAD
     */
    private void createTag(final String commit)
    {
        final TextField name = new TextField();
        name.setPrefColumnCount(25);
        final TextField message = new TextField();
        message.setPromptText(Messages.TagMessagePrompt);
        final GridPane grid = new GridPane();
        grid.setHgap(5);
        grid.setVgap(5);
        grid.addRow(0, new Label(Messages.TagName), name);
        grid.addRow(1, new Label(Messages.TagMessage), message);

        final Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle(Messages.CreateTag);
        dialog.setHeaderText(commit == null ? "HEAD" : commit);
        dialog.getDialogPane().setContent(grid);
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        DialogHelper.positionDialog(dialog, layout, -200, -100);
        Platform.runLater(name::requestFocus);

        if (dialog.showAndWait().orElse(ButtonType.CANCEL) == ButtonType.OK  &&  ! name.getText().isBlank())
        {
            final String tag_name = name.getText().trim();
            final String text = message.getText().trim();
            runAndRefresh(Messages.CreateTag, git -> git.createTag(tag_name, text, commit));
        }
    }

    /** Prompt for reset mode and reset the current branch to a commit
     *  @param commit Target commit
     */
    private void reset(final CommitInfo commit)
    {
        final ComboBox<ResetMode> mode = new ComboBox<>();
        mode.getItems().setAll(ResetMode.values());
        mode.setValue(ResetMode.MIXED);
        final Label info = new Label();
        info.setWrapText(true);
        info.setPrefWidth(380);
        final Runnable update_info = () -> info.setText(switch (mode.getValue())
        {
            case SOFT  -> Messages.ResetSoft;
            case MIXED -> Messages.ResetMixed;
            case HARD  -> Messages.ResetHard;
        });
        mode.setOnAction(event -> update_info.run());
        update_info.run();
        final GridPane grid = new GridPane();
        grid.setHgap(5);
        grid.setVgap(5);
        grid.addRow(0, new Label(Messages.ResetMode), mode);
        grid.add(info, 0, 1, 2, 1);

        final Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle(Messages.Reset);
        dialog.setHeaderText(Messages.ResetTo + " " + commit.id().substring(0, 7) + " " + commit.message());
        dialog.getDialogPane().setContent(grid);
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        DialogHelper.positionDialog(dialog, layout, -200, -100);
        if (dialog.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK)
            return;
        final ResetMode selected = mode.getValue();
        // Hard reset discards changes, ask again
        if (selected == ResetMode.HARD  &&  ! confirm(Messages.Reset, Messages.ResetHardConfirm))
            return;
        runAndRefresh(Messages.Reset, git -> git.reset(commit.id(), selected));
    }

    /** Prompt for stash message and stash local changes */
    private void stash()
    {
        final TextField message = new TextField();
        message.setPrefColumnCount(30);
        final CheckBox untracked = new CheckBox(Messages.StashUntracked);
        final GridPane grid = new GridPane();
        grid.setHgap(5);
        grid.setVgap(5);
        grid.addRow(0, new Label(Messages.StashMessage), message);
        grid.add(untracked, 1, 1);

        final Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle(Messages.Stash);
        dialog.setHeaderText(null);
        dialog.getDialogPane().setContent(grid);
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        DialogHelper.positionDialog(dialog, layout, -200, -100);
        Platform.runLater(message::requestFocus);

        if (dialog.showAndWait().orElse(ButtonType.CANCEL) == ButtonType.OK)
        {
            final String text = message.getText().trim();
            final boolean include_untracked = untracked.isSelected();
            runAndRefresh(Messages.Stash, git -> git.stash(text, include_untracked));
        }
    }

    /** Progress monitor that shows clone progress next to the busy indicator */
    private class BusyProgress extends BatchingProgressMonitor
    {
        @Override
        protected void onUpdate(final String task, final int done, final Duration duration)
        {
            show(task + " " + done);
        }

        @Override
        protected void onEndTask(final String task, final int done, final Duration duration)
        {
            show(task + " " + done);
        }

        @Override
        protected void onUpdate(final String task, final int done, final int total, final int percent,
                                final Duration duration)
        {
            show(task + " " + percent + "%");
        }

        @Override
        protected void onEndTask(final String task, final int done, final int total, final int percent,
                                 final Duration duration)
        {
            show(task + " " + percent + "%");
        }

        private void show(final String text)
        {
            Platform.runLater(() -> busy_label.setText(text));
        }
    }

    /** Prompt for URL and directory, clone, then open the clone */
    private void cloneRepository()
    {
        final TextField url = new TextField();
        url.setPrefColumnCount(40);
        url.setPromptText("https://host/group/repo.git, git@host:group/repo.git");
        final TextField parent = new TextField(System.getProperty("user.home"));
        final Button browse_parent = new Button(Messages.Browse);
        browse_parent.setOnAction(event ->
        {
            final DirectoryChooser chooser = new DirectoryChooser();
            final File current = new File(parent.getText());
            if (current.isDirectory())
                chooser.setInitialDirectory(current);
            final File dir = chooser.showDialog(url.getScene().getWindow());
            if (dir != null)
                parent.setText(dir.getAbsolutePath());
        });
        final TextField name = new TextField();
        // Suggest directory name from the URL, ".../repo.git" -> "repo"
        url.textProperty().addListener((p, o, text) ->
        {
            String suggestion = text.trim().replaceAll("/+$", "");
            suggestion = suggestion.substring(Math.max(suggestion.lastIndexOf('/'), suggestion.lastIndexOf(':')) + 1);
            if (suggestion.endsWith(".git"))
                suggestion = suggestion.substring(0, suggestion.length() - 4);
            name.setText(suggestion);
        });
        final GridPane grid = new GridPane();
        grid.setHgap(5);
        grid.setVgap(5);
        grid.addRow(0, new Label(Messages.RemoteUrl), url);
        grid.addRow(1, new Label(Messages.CloneParent), parent, browse_parent);
        grid.addRow(2, new Label(Messages.CloneName), name);

        final Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle(Messages.Clone);
        dialog.setHeaderText(null);
        dialog.getDialogPane().setContent(grid);
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        DialogHelper.positionDialog(dialog, layout, -200, -100);
        Platform.runLater(url::requestFocus);
        if (dialog.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK  ||
            url.getText().isBlank()  ||  name.getText().isBlank())
            return;

        final String address = url.getText().trim();
        final File directory = new File(parent.getText().trim(), name.getText().trim());
        schedule(Messages.Clone, monitor ->
        {
            try
            {
                GitRepository.cloneRepository(address, directory, new GitCredentialsProvider(layout), new BusyProgress());
                setRepository(directory);
            }
            catch (Exception ex)
            {
                showError(Messages.Clone, ex);
            }
        });
    }

    /** Prompt for pull options and pull */
    private void pull()
    {
        if (last_state == null)
            return;
        final BranchInfo current = last_state.branches().stream().filter(BranchInfo::current).findFirst().orElse(null);
        final Label upstream = new Label(current == null  ||  current.upstream() == null
                                         ? Messages.NoUpstream
                                         : last_state.branch() + " \u2190 " + current.upstream());
        final CheckBox rebase = new CheckBox(Messages.PullRebase);
        final GridPane grid = new GridPane();
        grid.setHgap(5);
        grid.setVgap(5);
        grid.addRow(0, new Label(Messages.Branch + ":"), upstream);
        grid.add(rebase, 1, 1);

        final Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle(Messages.Pull);
        dialog.setHeaderText(null);
        dialog.getDialogPane().setContent(grid);
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        DialogHelper.positionDialog(dialog, layout, -200, -100);
        if (dialog.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK)
            return;
        final boolean use_rebase = rebase.isSelected();
        runMayConflict(Messages.Pull, git -> git.pull(new GitCredentialsProvider(layout), use_rebase));
    }

    /** Prompt for push options and push */
    private void push()
    {
        if (last_state == null)
            return;
        final ComboBox<String> remote = new ComboBox<>();
        remote.getItems().setAll(last_state.remotes().keySet());
        final ComboBox<String> branch = new ComboBox<>();
        branch.getItems().setAll(last_state.branches().stream().map(BranchInfo::name).toList());
        branch.setValue(last_state.branches().stream().filter(BranchInfo::current).map(BranchInfo::name)
                                  .findFirst().orElse(branch.getItems().isEmpty() ? null : branch.getItems().get(0)));
        final Label upstream = new Label();
        // Default remote: Upstream of the branch, else 'origin' or first
        final Runnable update = () ->
        {
            final BranchInfo info = last_state.branches().stream()
                                              .filter(b -> b.name().equals(branch.getValue()))
                                              .findFirst().orElse(null);
            if (info != null  &&  info.upstream() != null)
            {
                upstream.setText(Messages.Upstream + ": " + info.upstream());
                final String upstream_remote = info.upstream().substring(0, info.upstream().indexOf('/'));
                if (remote.getItems().contains(upstream_remote))
                    remote.setValue(upstream_remote);
            }
            else
            {
                upstream.setText(Messages.UpstreamWillBeSet);
                if (remote.getValue() == null  &&  ! remote.getItems().isEmpty())
                    remote.setValue(remote.getItems().contains("origin") ? "origin" : remote.getItems().get(0));
            }
        };
        branch.setOnAction(event -> update.run());
        update.run();
        final CheckBox tags = new CheckBox(Messages.PushTags);
        final CheckBox force = new CheckBox(Messages.ForcePush);
        final Label force_info = new Label(Messages.ForcePushInfo);
        force_info.setWrapText(true);
        force_info.setPrefWidth(380);
        force_info.setStyle("-fx-text-fill: #c62828;");
        force_info.visibleProperty().bind(force.selectedProperty());
        force_info.managedProperty().bind(force_info.visibleProperty());

        final GridPane grid = new GridPane();
        grid.setHgap(5);
        grid.setVgap(5);
        grid.addRow(0, new Label(Messages.RemoteName), remote);
        grid.addRow(1, new Label(Messages.Branch + ":"), branch);
        grid.add(upstream, 1, 2);
        grid.add(tags, 1, 3);
        grid.add(force, 1, 4);
        grid.add(force_info, 0, 5, 2, 1);

        final Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle(Messages.Push);
        dialog.setHeaderText(null);
        dialog.getDialogPane().setContent(grid);
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        DialogHelper.positionDialog(dialog, layout, -200, -100);
        if (dialog.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK  ||
            remote.getValue() == null  ||  branch.getValue() == null)
            return;
        final PushOptions options = new PushOptions(remote.getValue(), branch.getValue(), tags.isSelected(), force.isSelected());
        runAndRefresh(Messages.Push, git -> pushAndOfferLogbook(git, options));
    }

    /** Prompt for pattern to ignore, based on an untracked file, and add it to .gitignore
     *  @param path Untracked file
     */
    private void ignore(final String path)
    {
        // Offer the file, files with the same extension, the directory
        final List<String> patterns = new ArrayList<>();
        patterns.add(path);
        final String name = new File(path).getName();
        final int dot = name.lastIndexOf('.');
        if (dot > 0)
            patterns.add("*" + name.substring(dot));
        final String parent = new File(path).getParent();
        if (parent != null)
            patterns.add(parent.replace(File.separatorChar, '/') + "/");
        final ChoiceDialog<String> dialog = new ChoiceDialog<>(patterns.get(0), patterns);
        dialog.setTitle(Messages.Ignore);
        dialog.setHeaderText(Messages.IgnoreInfo);
        dialog.setContentText(Messages.IgnorePattern);
        DialogHelper.positionDialog(dialog, layout, -200, -100);
        dialog.showAndWait().ifPresent(pattern -> runAndRefresh(Messages.Ignore, git -> git.addToIgnore(pattern)));
    }

    /** Preferences node for the list of recent repositories */
    private static final String RECENT = "recent_repositories";

    /** Maximum number of recent repositories */
    private static final int MAX_RECENT = 10;

    /** @return Recently opened repositories, most recent first */
    private static List<String> getRecent()
    {
        final String text = java.util.prefs.Preferences.userNodeForPackage(GitApp.class).get(RECENT, "");
        return text.isEmpty() ? List.of() : List.of(text.split("\n"));
    }

    /** @param path Repository to add to the recent repositories */
    private static void addRecent(final String path)
    {
        final List<String> list = new ArrayList<>(getRecent());
        list.remove(path);
        list.add(0, path);
        while (list.size() > MAX_RECENT)
            list.remove(list.size() - 1);
        java.util.prefs.Preferences.userNodeForPackage(GitApp.class).put(RECENT, String.join("\n", list));
    }

    /** Update menu of recent repositories */
    private void updateRecentMenu()
    {
        recent.getItems().clear();
        for (String path : getRecent())
        {
            final MenuItem item = new MenuItem(path);
            item.setOnAction(event -> setRepository(new File(path)));
            recent.getItems().add(item);
        }
        recent.setDisable(recent.getItems().isEmpty());
    }

    /** Prompt for directory, create new repository there and open it */
    private void createRepository()
    {
        final TextField path = new TextField();
        path.setPrefColumnCount(40);
        final Button browse = new Button(Messages.Browse);
        browse.setOnAction(event ->
        {
            final DirectoryChooser chooser = new DirectoryChooser();
            final File current = new File(path.getText());
            if (current.isDirectory())
                chooser.setInitialDirectory(current);
            final File chosen = chooser.showDialog(path.getScene().getWindow());
            if (chosen != null)
                path.setText(chosen.getAbsolutePath());
        });
        final GridPane grid = new GridPane();
        grid.setHgap(5);
        grid.setVgap(5);
        grid.addRow(0, new Label(Messages.Directory), path, browse);

        final Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle(Messages.NewRepository);
        dialog.setHeaderText(null);
        dialog.getDialogPane().setContent(grid);
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        DialogHelper.positionDialog(dialog, layout, -200, -100);
        Platform.runLater(path::requestFocus);
        if (dialog.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK  ||  path.getText().isBlank())
            return;
        final File dir = new File(path.getText().trim());
        schedule(Messages.NewRepository, monitor ->
        {
            try
            {
                GitRepository.init(dir);
                setRepository(dir);
            }
            catch (Exception ex)
            {
                showError(Messages.NewRepository, ex);
            }
        });
    }

    private void browse()
    {
        final DirectoryChooser chooser = new DirectoryChooser();
        if (repo != null)
            chooser.setInitialDirectory(repo.getWorkTree());
        final File dir = chooser.showDialog(layout.getScene().getWindow());
        if (dir != null)
            setRepository(dir);
    }

    /** @param directory Directory in a git work tree to show */
    public void setRepository(final File directory)
    {
        schedule(Messages.DisplayName, monitor ->
        {
            try
            {
                final GitRepository new_repo = new GitRepository(directory);
                log_count = GitApp.log_count;
                final GitRepository old = repo;
                repo = new_repo;
                if (old != null)
                    old.close();
                startWatcher();
                addRecent(new_repo.getWorkTree().getAbsolutePath());
                Platform.runLater(() ->
                {
                    repo_path.setText(new_repo.getWorkTree().getAbsolutePath());
                    updateRecentMenu();
                    sidebar.selectFileStatus();
                });
                refresh();
            }
            catch (Exception ex)
            {
                showError(Messages.Repository, ex);
            }
        });
    }

    /** Re-read repository state in background */
    private void refresh()
    {
        final GitRepository current = repo;
        if (current == null)
            return;
        schedule(Messages.Refresh, monitor ->
        {
            try
            {
                final State state = current.getState(log_count, all_branches, search);
                Platform.runLater(() -> update(state));
            }
            catch (Exception ex)
            {
                showError(Messages.Refresh, ex);
            }
        });
    }

    /** @param status Staged and unstaged changes
     *  @return Changed files compared to HEAD, one entry per file
     */
    private static List<CommitFile> getUncommittedFiles(final FileStatus status)
    {
        // A file can be staged and unstaged. Compared to HEAD, a new file stays 'added',
        // otherwise the state of the work tree applies
        final Map<String, ChangeType> files = new TreeMap<>();
        for (StatusEntry entry : status.staged())
            files.put(entry.path(), entry.type());
        for (StatusEntry entry : status.unstaged())
            if (files.get(entry.path()) != ChangeType.ADDED)
                files.put(entry.path(), entry.type());
        return files.entrySet()
                    .stream()
                    .map(entry -> new CommitFile(entry.getValue(), entry.getKey(), null))
                    .toList();
    }

    /** @param state Repository state to show */
    private void update(final State state)
    {
        last_state = state;
        file_status.setChanges(state.status());
        final File absolute = pending_absolute;
        if (absolute != null  &&  getWorkTree() != null)
        {
            pending_absolute = null;
            pending_select = relative(getWorkTree(), absolute);
        }
        final String select = pending_select;
        if (select != null)
        {
            pending_select = null;
            file_status.selectFile(select);
        }
        final String current_branch = state.branches().stream()
                                           .filter(BranchInfo::current)
                                           .map(b -> Constants.R_HEADS + b.name())
                                           .findFirst().orElse(null);
        history.setCommits(state.log(), current_branch, log_count, getUncommittedFiles(state.status()));
        stash_diff.clear();
        // Update side bar last, it may re-select a branch and thus a commit in the history
        sidebar.update(state);

        // Show number of commits to pull/push for the current branch
        final BranchInfo current = state.branches().stream().filter(BranchInfo::current).findFirst().orElse(null);
        pull.setText(current != null  &&  current.behind() > 0 ? Messages.Pull + " (" + current.behind() + ")" : Messages.Pull);
        push.setText(current != null  &&  current.ahead()  > 0 ? Messages.Push + " (" + current.ahead()  + ")" : Messages.Push);
    }

    /** Repository operation that may fail */
    @FunctionalInterface
    private interface GitOperation
    {
        void run(GitRepository git) throws Exception;
    }

    private void runAndRefresh(final String name, final GitOperation operation)
    {
        final GitRepository current = repo;
        if (current == null)
            return;
        // Files of the work tree open in phoebus tabs, to detect if the operation changed them
        final List<OpenFiles.OpenFile> open = Platform.isFxApplicationThread()
                                            ? OpenFiles.list(current.getWorkTree())
                                            : List.of();
        final Map<File, String> before = OpenFiles.stamp(open.stream().map(OpenFiles.OpenFile::file).toList());
        schedule(name, monitor ->
        {
            try
            {
                operation.run(current);
            }
            catch (Exception ex)
            {
                showError(name, ex);
            }
            refresh();
            if (! open.isEmpty())
            {
                final Map<File, String> after = OpenFiles.stamp(before.keySet());
                final List<OpenFiles.OpenFile> changed = open.stream()
                                                             .filter(o -> ! before.get(o.file()).equals(after.get(o.file())))
                                                             .toList();
                if (! changed.isEmpty())
                    Platform.runLater(() -> handleChangedOpenFiles(changed));
            }
        });
    }

    /** Offer to reload open files that a git operation changed, warn about unsaved ones
     *  @param changed Open files that changed on disk
     */
    private void handleChangedOpenFiles(final List<OpenFiles.OpenFile> changed)
    {
        final List<OpenFiles.OpenFile> dirty = changed.stream().filter(o -> o.item().isDirty()).toList();
        final List<OpenFiles.OpenFile> clean = changed.stream().filter(o -> ! o.item().isDirty()).toList();
        if (! clean.isEmpty())
        {
            final String files = clean.stream().map(o -> o.file().getPath()).collect(Collectors.joining("\n"));
            if (confirm(Messages.Reload, Messages.ChangedOpenFiles + "\n\n" + files))
                clean.forEach(OpenFiles::reload);
        }
        if (! dirty.isEmpty())
        {
            final String files = dirty.stream().map(o -> o.file().getPath()).collect(Collectors.joining("\n"));
            final Alert alert = new Alert(AlertType.WARNING, Messages.ChangedDirtyFiles + "\n\n" + files, ButtonType.OK);
            alert.setTitle(Messages.Reload);
            alert.setHeaderText(null);
            DialogHelper.positionDialog(alert, layout, -200, -100);
            alert.showAndWait();
        }
    }

    /** Repository operation that may stop with conflicts */
    @FunctionalInterface
    private interface ConflictingOperation
    {
        /** @return <code>true</code> if there are conflicts to resolve */
        boolean run(GitRepository git) throws Exception;
    }

    /** Run merge, cherry-pick, revert. On conflicts, show the file status to resolve them */
    private void runMayConflict(final String name, final ConflictingOperation operation)
    {
        runAndRefresh(name, git ->
        {
            if (operation.run(git))
                Platform.runLater(() ->
                {
                    sidebar.selectFileStatus();
                    final Alert alert = new Alert(AlertType.INFORMATION, Messages.ConflictsInfo, ButtonType.OK);
                    alert.setTitle(name);
                    alert.setHeaderText(null);
                    DialogHelper.positionDialog(alert, layout, -200, -100);
                    alert.show();
                });
        });
    }

    private void showError(final String operation, final Exception ex)
    {
        GitApp.logger.log(Level.WARNING, "Git " + operation + " failed", ex);
        ExceptionDetailsErrorDialog.openError(layout, Messages.ErrorTitle, operation, ex);
    }

    private void dispose()
    {
        views.remove(this);
        stopWatcher();
        watchFocus(null);
        final GitRepository current = repo;
        repo = null;
        if (current != null)
            current.close();
    }

    @Override
    public AppDescriptor getAppDescriptor()
    {
        return app;
    }

    @Override
    public void restore(final Memento memento)
    {
        memento.getBoolean(ALL_BRANCHES).ifPresent(all ->
        {
            all_branches = all;
            history.setAllBranches(all);
        });
        memento.getString(REPOSITORY).ifPresent(dir -> setRepository(new File(dir)));
    }

    @Override
    public void save(final Memento memento)
    {
        final GitRepository current = repo;
        if (current != null)
            memento.setString(REPOSITORY, current.getWorkTree().getAbsolutePath());
        memento.setBoolean(ALL_BRANCHES, all_branches);
    }
}
