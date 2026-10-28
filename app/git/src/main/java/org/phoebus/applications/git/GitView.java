package org.phoebus.applications.git;

import java.io.File;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.eclipse.jgit.api.errors.NotMergedException;
import org.eclipse.jgit.lib.Constants;
import org.phoebus.applications.git.GitRepository.BranchInfo;
import org.phoebus.applications.git.GitRepository.CommitInfo;
import org.phoebus.applications.git.GitRepository.RemoteBranch;
import org.phoebus.applications.git.GitRepository.ResetMode;
import org.phoebus.applications.git.GitRepository.State;
import org.phoebus.applications.git.GitRepository.StatusEntry;
import org.phoebus.applications.git.GitRepository.StashInfo;
import org.phoebus.applications.git.GitRepository.TagInfo;
import org.phoebus.framework.jobs.JobManager;
import org.phoebus.framework.jobs.JobRunnable;
import org.phoebus.framework.persistence.Memento;
import org.phoebus.framework.spi.AppDescriptor;
import org.phoebus.framework.spi.AppInstance;
import org.phoebus.ui.dialog.DialogHelper;
import org.phoebus.ui.dialog.ExceptionDetailsErrorDialog;
import org.phoebus.ui.docking.DockItem;
import org.phoebus.ui.docking.DockPane;
import org.phoebus.ui.javafx.ImageCache;

import javafx.application.Platform;
import javafx.geometry.Pos;
import javafx.scene.control.Alert;
import javafx.scene.control.Alert.AlertType;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
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
import javafx.stage.DirectoryChooser;

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

    /** Shown while operations are running */
    private final ProgressIndicator busy = new ProgressIndicator();
    private final Label busy_label = new Label();

    /** Number of running operations */
    private final AtomicInteger active = new AtomicInteger();

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
        content.setTop(createToolbar());

        final DockItem tab = new DockItem(this, content);
        DockPane.getActiveDockPane().addTab(tab);
        tab.addClosedNotification(this::dispose);

        if (directory != null)
            setRepository(directory);
    }

    private ToolBar createToolbar()
    {
        final Button fetch = new Button(Messages.Fetch);
        fetch.setOnAction(event -> runAndRefresh(Messages.Fetch, git -> git.fetch(new GitCredentialsProvider(layout))));

        pull.setOnAction(event -> runAndRefresh(Messages.Pull, git -> git.pull(new GitCredentialsProvider(layout))));
        push.setOnAction(event -> runAndRefresh(Messages.Push, git -> git.push(new GitCredentialsProvider(layout))));

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
        repo_path.setPrefColumnCount(30);
        final Button browse = new Button(Messages.Browse);
        browse.setOnAction(event -> browse());
        final HBox repo_box = new HBox(5, new Label(Messages.Repository), repo_path, browse);
        repo_box.setAlignment(Pos.CENTER_RIGHT);
        HBox.setHgrow(repo_box, Priority.ALWAYS);
        HBox.setHgrow(repo_path, Priority.ALWAYS);

        busy.setPrefSize(18, 18);
        busy.setVisible(false);
        busy_label.setVisible(false);

        return new ToolBar(fetch, pull, push, new Separator(), branch, merge, tag, stash, new Separator(), refresh,
                           busy, busy_label, repo_box);
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
        public void commit(final String message, final boolean amend)
        {
            runAndRefresh(Messages.Commit, git ->
            {
                git.commit(message, amend);
                Platform.runLater(file_status::clearMessage);
            });
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
                Platform.runLater(() ->
                {
                    repo_path.setText(new_repo.getWorkTree().getAbsolutePath());
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
                final State state = current.getState(log_count, all_branches);
                Platform.runLater(() -> update(state));
            }
            catch (Exception ex)
            {
                showError(Messages.Refresh, ex);
            }
        });
    }

    /** @param state Repository state to show */
    private void update(final State state)
    {
        last_state = state;
        file_status.setChanges(state.status());
        final String current_branch = state.branches().stream()
                                           .filter(BranchInfo::current)
                                           .map(b -> Constants.R_HEADS + b.name())
                                           .findFirst().orElse(null);
        final List<String> changed = Stream.concat(state.status().staged().stream(),
                                                                    state.status().unstaged().stream())
                                                            .map(StatusEntry::path)
                                                            .distinct()
                                                            .sorted()
                                                            .toList();
        history.setCommits(state.log(), current_branch, log_count, changed);
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
        });
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
