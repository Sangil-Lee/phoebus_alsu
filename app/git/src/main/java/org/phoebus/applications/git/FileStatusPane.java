package org.phoebus.applications.git;

import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

import org.phoebus.applications.git.GitRepository.ChangeType;
import org.phoebus.applications.git.GitRepository.FileStatus;
import org.phoebus.applications.git.GitRepository.StatusEntry;

import javafx.beans.binding.Bindings;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.geometry.Insets;
import javafx.geometry.Orientation;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.input.MouseButton;
import javafx.scene.control.SelectionMode;
import javafx.scene.control.SplitPane;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextArea;
import javafx.scene.control.TitledPane;
import javafx.scene.control.cell.CheckBoxTableCell;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

/** Staged and unstaged files, diff of the selected file, commit message */
@SuppressWarnings("nls")
class FileStatusPane extends BorderPane
{
    /** Actions triggered from the file status */
    interface Actions
    {
        void stage(List<String> paths);
        void unstage(List<String> paths);
        /** Discard unstaged changes, after asking user */
        void discard(List<StatusEntry> entries);
        /** @param push Push after the commit? */
        void commit(String message, boolean amend, boolean push);
        /** Get message of last commit, for amend */
        void getHeadMessage(Consumer<String> handler);
        /** Abort merge, cherry-pick or revert, after asking user */
        void abortMerge();
        /** @param path File to show in the file history */
        void showFileHistory(String path);
        /** Stage one hunk of the unstaged diff */
        void stageHunk(String path, int hunk);
        /** Unstage one hunk of the staged diff */
        void unstageHunk(String path, int hunk);
        /** Discard one hunk of the unstaged diff, after asking user */
        void discardHunk(String path, int hunk);
        /** @param ours Resolve conflict with our version, or theirs? */
        void resolveConflict(List<String> paths, boolean ours);
        /** Prompt for pattern and add it to .gitignore */
        void ignore(String path);
        void continueRebase();
        void skipRebase();
        /** Abort rebase, after asking user */
        void abortRebase();
        /** @param editor Open display in editor, otherwise default application */
        void openFile(String path, boolean editor);
    }

    /** Row of a file table: Changed file and check box that stages/unstages it */
    private static class FileRow
    {
        final StatusEntry entry;
        final BooleanProperty checked;

        FileRow(final StatusEntry entry, final boolean checked)
        {
            this.entry = entry;
            this.checked = new SimpleBooleanProperty(checked);
        }
    }

    private final Actions actions;
    private final GitActions git_actions;
    private final TableView<FileRow> staged = new TableView<>();
    private final TableView<FileRow> unstaged = new TableView<>();
    private final DiffPane diff;
    private final TextArea commit_message = new TextArea();
    private final CheckBox amend = new CheckBox(Messages.Amend);
    private final Button commit = new Button(Messages.Commit);
    private final Button abort = new Button(Messages.AbortMerge);
    private final CheckBox push = new CheckBox(Messages.CommitAndPush);
    private final Button continue_rebase = new Button(Messages.ContinueRebase);
    private final Button skip_rebase = new Button(Messages.SkipRebase);
    private final Button abort_rebase = new Button(Messages.AbortRebase);
    private final Label rebase_info = new Label(Messages.RebaseInProgress);

    /** Is a merge in progress? */
    private boolean merging = false;

    /** Is a rebase in progress? */
    private boolean rebasing = false;

    /** File shown in the diff, restored after refresh */
    private String diff_path = null;
    private boolean diff_staged = false;

    /** @param repo Provides the current repository
     *  @param on_error Error handler
     *  @param actions Actions
     */
    FileStatusPane(final Supplier<GitRepository> repo, final Consumer<Exception> on_error, final Actions actions,
                   final GitActions git_actions)
    {
        this.actions = actions;
        this.git_actions = git_actions;
        diff = new DiffPane(repo, on_error);

        configureTable(staged, true);
        configureTable(unstaged, false);

        final Button unstage_all = new Button(Messages.UnstageAll);
        unstage_all.setOnAction(event -> actions.unstage(getPaths(staged.getItems())));
        unstage_all.disableProperty().bind(Bindings.isEmpty(staged.getItems()));
        final Button stage_all = new Button(Messages.StageAll);
        stage_all.setOnAction(event -> actions.stage(getPaths(unstaged.getItems())));
        stage_all.disableProperty().bind(Bindings.isEmpty(unstaged.getItems()));

        final SplitPane files = new SplitPane(createSection(Messages.StagedFiles, unstage_all, staged),
                                              createSection(Messages.UnstagedFiles, stage_all, unstaged));
        files.setOrientation(Orientation.VERTICAL);

        final TitledPane diff_pane = new TitledPane(Messages.Diff, diff);
        diff_pane.setCollapsible(false);
        diff_pane.setMaxHeight(Double.MAX_VALUE);
        final SplitPane split = new SplitPane(files, diff_pane);
        split.setDividerPositions(0.4);

        commit_message.setPromptText(Messages.CommitMessagePrompt);
        commit_message.setPrefRowCount(3);
        commit_message.setWrapText(true);
        HBox.setHgrow(commit_message, Priority.ALWAYS);
        amend.setOnAction(event ->
        {
            // Start with the message of the commit to amend
            if (amend.isSelected()  &&  commit_message.getText().isBlank())
                actions.getHeadMessage(message ->
                {
                    if (message != null  &&  commit_message.getText().isBlank())
                        commit_message.setText(message.trim());
                });
            updateCommitButton();
        });
        commit.setMaxWidth(Double.MAX_VALUE);
        commit.setOnAction(event ->
        {
            final String message = commit_message.getText().trim();
            if (message.isEmpty())
                commit_message.requestFocus();
            else
                actions.commit(message, amend.isSelected(), push.isSelected());
        });
        // Abort is only shown while merging
        abort.setMaxWidth(Double.MAX_VALUE);
        abort.setOnAction(event -> actions.abortMerge());
        abort.managedProperty().bind(abort.visibleProperty());
        abort.setVisible(false);
        // Rebase buttons are only shown while rebasing, instead of commit
        continue_rebase.setOnAction(event -> actions.continueRebase());
        skip_rebase.setOnAction(event -> actions.skipRebase());
        abort_rebase.setOnAction(event -> actions.abortRebase());
        for (var node : List.of(continue_rebase, skip_rebase, abort_rebase, rebase_info))
        {
            node.setMaxWidth(Double.MAX_VALUE);
            node.managedProperty().bind(node.visibleProperty());
            node.setVisible(false);
        }
        rebase_info.setStyle("-fx-text-fill: #6a1b9a; -fx-font-weight: bold;");
        for (var node : List.of(commit, amend, push))
            node.managedProperty().bind(node.visibleProperty());
        final VBox commit_box = new VBox(5, rebase_info, continue_rebase, skip_rebase, abort_rebase,
                                         commit, abort, amend, push);
        commit_box.setAlignment(Pos.BOTTOM_LEFT);
        final HBox bottom = new HBox(5, commit_message, commit_box);
        bottom.setPadding(new Insets(5));

        setCenter(split);
        setBottom(bottom);
        updateCommitButton();
    }

    /** @param title Section title
     *  @param button Button shown next to the title
     *  @param table File table
     *  @return Section with title, button and table
     */
    private static VBox createSection(final String title, final Button button, final TableView<FileRow> table)
    {
        final Label label = new Label(title);
        label.setStyle("-fx-font-weight: bold;");
        final Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        final HBox header = new HBox(5, label, spacer, button);
        header.setAlignment(Pos.CENTER_LEFT);
        header.setPadding(new Insets(3, 5, 3, 5));
        VBox.setVgrow(table, Priority.ALWAYS);
        return new VBox(header, table);
    }

    /** @param table File table to configure
     *  @param is_staged Table for staged files?
     */
    private void configureTable(final TableView<FileRow> table, final boolean is_staged)
    {
        final TableColumn<FileRow, Boolean> check_col = new TableColumn<>();
        check_col.setSortable(false);
        check_col.setPrefWidth(30);
        check_col.setCellValueFactory(cell -> cell.getValue().checked);
        check_col.setCellFactory(CheckBoxTableCell.forTableColumn(check_col));
        table.getColumns().add(check_col);

        final TableColumn<FileRow, ChangeType> type_col = new TableColumn<>(Messages.ColState);
        type_col.setPrefWidth(85);
        type_col.setCellValueFactory(cell -> new ReadOnlyObjectWrapper<>(cell.getValue().entry.type()));
        type_col.setCellFactory(col -> new ChangeTypeCell());
        table.getColumns().add(type_col);

        table.getColumns().add(TableHelper.createColumn(Messages.ColPath, 300, row -> row.entry.path()));
        // Check boxes are only active in editable table
        table.setEditable(true);
        table.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
        table.setPlaceholder(new Label(""));

        // Selecting a file in one table shows its diff and clears the selection in the other table
        final TableView<FileRow> other = is_staged ? unstaged : staged;
        table.getSelectionModel().selectedItemProperty().addListener((prop, old, row) ->
        {
            if (row == null)
                return;
            other.getSelectionModel().clearSelection();
            showDiff(row.entry, is_staged);
        });

        final ContextMenu menu = new ContextMenu();
        if (is_staged)
        {
            final MenuItem unstage = new MenuItem(Messages.Unstage);
            unstage.setOnAction(event -> actions.unstage(getPaths(table.getSelectionModel().getSelectedItems())));
            menu.getItems().add(unstage);
        }
        else
        {
            final MenuItem stage = new MenuItem(Messages.Stage);
            stage.setOnAction(event -> actions.stage(getPaths(table.getSelectionModel().getSelectedItems())));
            final MenuItem discard = new MenuItem(Messages.Discard);
            discard.setOnAction(event -> actions.discard(table.getSelectionModel()
                                                              .getSelectedItems()
                                                              .stream()
                                                              .map(row -> row.entry)
                                                              .toList()));
            menu.getItems().addAll(stage, discard);
        }
        // Conflicts: Use one side
        final MenuItem ours = new MenuItem(Messages.ResolveOurs);
        ours.setOnAction(event -> actions.resolveConflict(getPaths(table.getSelectionModel().getSelectedItems()), true));
        final MenuItem theirs = new MenuItem(Messages.ResolveTheirs);
        theirs.setOnAction(event -> actions.resolveConflict(getPaths(table.getSelectionModel().getSelectedItems()), false));
        // Untracked: Ignore
        final MenuItem ignore = new MenuItem(Messages.Ignore);
        ignore.setOnAction(event -> actions.ignore(table.getSelectionModel().getSelectedItem().entry.path()));
        final MenuItem history = new MenuItem(Messages.FileHistory);
        history.setOnAction(event -> actions.showFileHistory(table.getSelectionModel().getSelectedItem().entry.path()));
        // Conflict: Merge tool
        final MenuItem merge_tool = new MenuItem(Messages.MergeToolMenu);
        merge_tool.setOnAction(event -> git_actions.openMergeTool(table.getSelectionModel().getSelectedItem().entry.path()));
        // Compare with or replace with other version
        final Menu compare = git_actions.createCompareMenu(() -> getPaths(table.getSelectionModel().getSelectedItems()));
        final Menu restore = git_actions.createRestoreMenu(() -> getPaths(table.getSelectionModel().getSelectedItems()));
        // Open file, displays also in editor
        final MenuItem open = new MenuItem(Messages.Open);
        open.setOnAction(event -> actions.openFile(table.getSelectionModel().getSelectedItem().entry.path(), false));
        final MenuItem edit = new MenuItem(Messages.OpenInEditor);
        edit.setOnAction(event -> actions.openFile(table.getSelectionModel().getSelectedItem().entry.path(), true));
        menu.getItems().addAll(0, List.of(open, edit, new SeparatorMenuItem()));
        menu.getItems().addAll(merge_tool, ours, theirs, ignore, history, new SeparatorMenuItem(), compare, restore);
        // Double-click opens the file
        table.setOnMouseClicked(event ->
        {
            final FileRow row = table.getSelectionModel().getSelectedItem();
            if (event.getButton() == MouseButton.PRIMARY  &&  event.getClickCount() == 2  &&  row != null)
            {
                if (row.entry.type() == ChangeType.CONFLICT)
                    git_actions.openMergeTool(row.entry.path());
                else if (row.entry.type() != ChangeType.DELETED)
                    actions.openFile(row.entry.path(), false);
            }
        });
        menu.setOnShowing(event ->
        {
            final List<FileRow> selected = table.getSelectionModel().getSelectedItems();
            final boolean none = selected.isEmpty();
            menu.getItems().forEach(item -> item.setDisable(none));
            final boolean conflicts = ! none  &&  selected.stream().allMatch(row -> row.entry.type() == ChangeType.CONFLICT);
            ours.setVisible(conflicts);
            theirs.setVisible(conflicts);
            merge_tool.setVisible(conflicts  &&  selected.size() == 1);
            // Untracked files have no other version
            final boolean tracked = ! none  &&  selected.stream().noneMatch(row -> row.entry.type() == ChangeType.UNTRACKED  ||
                                                                                   row.entry.type() == ChangeType.CONFLICT);
            compare.setDisable(! tracked);
            restore.setDisable(! tracked);
            ignore.setVisible(selected.size() == 1  &&  selected.get(0).entry.type() == ChangeType.UNTRACKED);
            final boolean single_file = selected.size() == 1  &&  selected.get(0).entry.type() != ChangeType.DELETED;
            open.setDisable(! single_file);
            edit.setDisable(! single_file);
            edit.setVisible(single_file  &&  OpenFiles.isDisplay(selected.get(0).entry.path()));
        });
        table.setContextMenu(menu);
    }

    /** Colored change type */
    private static class ChangeTypeCell extends TableCell<FileRow, ChangeType>
    {
        @Override
        protected void updateItem(final ChangeType type, final boolean empty)
        {
            super.updateItem(type, empty);
            if (empty  ||  type == null)
            {
                setText(null);
                setStyle(null);
                return;
            }
            setText(type.toString());
            final String color = getColor(type);
            setStyle("-fx-text-fill: " + color + "; -fx-font-weight: bold;");
        }
    }

    /** @param type Kind of change
     *  @return Text color for showing the change
     */
    static String getColor(final ChangeType type)
    {
        return switch (type)
        {
            case ADDED     -> "#2e7d32";
            case MODIFIED  -> "#e65100";
            case DELETED   -> "#c62828";
            case RENAMED   -> "#1565c0";
            case UNTRACKED -> "#757575";
            case CONFLICT  -> "#6a1b9a";
        };
    }

    private void showDiff(final StatusEntry entry, final boolean is_staged)
    {
        final String path = entry.path();
        diff_path = path;
        diff_staged = is_staged;
        // Hunk actions for changed content, not for conflicts, untracked or deleted files
        final DiffPane.HunkActions hunk_actions;
        if (is_staged)
            hunk_actions = new DiffPane.HunkActions()
            {
                @Override
                public List<String> getLabels()
                {
                    return List.of(Messages.UnstageHunk);
                }

                @Override
                public void run(final int action, final int hunk)
                {
                    actions.unstageHunk(path, hunk);
                }
            };
        else if (entry.type() == ChangeType.MODIFIED)
            hunk_actions = new DiffPane.HunkActions()
            {
                @Override
                public List<String> getLabels()
                {
                    return List.of(Messages.StageHunk, Messages.DiscardHunk);
                }

                @Override
                public void run(final int action, final int hunk)
                {
                    if (action == 0)
                        actions.stageHunk(path, hunk);
                    else
                        actions.discardHunk(path, hunk);
                }
            };
        else
            hunk_actions = null;
        // Display files can also be compared by widget
        final DiffPane.VersionsQuery widget_query = BobDiff.supports(path)
            ? git -> is_staged ? git.getStagedVersions(path) : git.getUnstagedVersions(path)
            : null;
        diff.show(git -> is_staged ? git.getStagedDiff(path) : git.getUnstagedDiff(path), hunk_actions, widget_query, path);
    }

    private static List<String> getPaths(final List<FileRow> rows)
    {
        return rows.stream().map(row -> row.entry.path()).toList();
    }

    /** @param status Staged and unstaged files */
    void setChanges(final FileStatus status)
    {
        staged.getItems().setAll(createRows(status.staged(), true));
        unstaged.getItems().setAll(createRows(status.unstaged(), false));
        merging = status.merging();
        rebasing = status.rebasing();
        abort.setVisible(merging);
        for (var node : List.of(continue_rebase, skip_rebase, abort_rebase, rebase_info))
            node.setVisible(rebasing);
        for (var node : List.of(commit, amend, push))
            node.setVisible(! rebasing);
        // Offer the prepared merge message
        if (merging  &&  status.merge_message() != null  &&  commit_message.getText().isBlank())
            commit_message.setText(status.merge_message());
        updateCommitButton();

        // Show the same file again, preferring the same table.
        // A file that was just staged or unstaged is found in the other table
        diff.clear();
        if (diff_path != null  &&
            ! select(diff_staged ? staged : unstaged, diff_path)  &&
            ! select(diff_staged ? unstaged : staged, diff_path))
            diff_path = null;
    }

    /** Select a file, in the unstaged or staged list, to show its diff
     *  @param path File path relative to the work tree
     */
    void selectFile(final String path)
    {
        if (! select(unstaged, path))
            select(staged, path);
    }

    /** @return <code>true</code> if path was found and selected */
    private static boolean select(final TableView<FileRow> table, final String path)
    {
        for (FileRow row : table.getItems())
            if (row.entry.path().equals(path))
            {
                table.getSelectionModel().clearSelection();
                table.getSelectionModel().select(row);
                table.scrollTo(row);
                return true;
            }
        return false;
    }

    /** @param entries Files
     *  @param is_staged Rows for the staged files?
     *  @return Rows with check box that unstages respectively stages the file
     */
    private List<FileRow> createRows(final List<StatusEntry> entries, final boolean is_staged)
    {
        return entries.stream().map(entry ->
        {
            final FileRow row = new FileRow(entry, is_staged);
            row.checked.addListener((prop, old, checked) ->
            {
                if (is_staged  &&  ! checked)
                    actions.unstage(List.of(entry.path()));
                else if (! is_staged  &&  checked)
                    actions.stage(List.of(entry.path()));
            });
            return row;
        }).toList();
    }

    /** Commit is possible with staged files, to amend the last commit, or to conclude a merge */
    private void updateCommitButton()
    {
        commit.setDisable(staged.getItems().isEmpty()  &&  ! amend.isSelected()  &&  ! merging);
    }

    /** Clear commit message and amend option after successful commit */
    void clearMessage()
    {
        commit_message.clear();
        amend.setSelected(false);
        updateCommitButton();
    }
}
