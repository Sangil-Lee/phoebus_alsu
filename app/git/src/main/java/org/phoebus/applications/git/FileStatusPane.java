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
import javafx.scene.control.MenuItem;
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
        void commit(String message, boolean amend);
        /** Get message of last commit, for amend */
        void getHeadMessage(Consumer<String> handler);
        /** Abort merge, cherry-pick or revert, after asking user */
        void abortMerge();
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
    private final TableView<FileRow> staged = new TableView<>();
    private final TableView<FileRow> unstaged = new TableView<>();
    private final DiffPane diff;
    private final TextArea commit_message = new TextArea();
    private final CheckBox amend = new CheckBox(Messages.Amend);
    private final Button commit = new Button(Messages.Commit);
    private final Button abort = new Button(Messages.AbortMerge);

    /** Is a merge in progress? */
    private boolean merging = false;

    /** File shown in the diff, restored after refresh */
    private String diff_path = null;
    private boolean diff_staged = false;

    /** @param repo Provides the current repository
     *  @param on_error Error handler
     *  @param actions Actions
     */
    FileStatusPane(final Supplier<GitRepository> repo, final Consumer<Exception> on_error, final Actions actions)
    {
        this.actions = actions;
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
                actions.commit(message, amend.isSelected());
        });
        // Abort is only shown while merging
        abort.setMaxWidth(Double.MAX_VALUE);
        abort.setOnAction(event -> actions.abortMerge());
        abort.managedProperty().bind(abort.visibleProperty());
        abort.setVisible(false);
        final VBox commit_box = new VBox(5, commit, abort, amend);
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
            showDiff(row.entry.path(), is_staged);
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
        menu.setOnShowing(event ->
        {
            final boolean none = table.getSelectionModel().isEmpty();
            menu.getItems().forEach(item -> item.setDisable(none));
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
            final String color = switch (type)
            {
                case ADDED     -> "#2e7d32";
                case MODIFIED  -> "#e65100";
                case DELETED   -> "#c62828";
                case UNTRACKED -> "#757575";
                case CONFLICT  -> "#6a1b9a";
            };
            setStyle("-fx-text-fill: " + color + "; -fx-font-weight: bold;");
        }
    }

    private void showDiff(final String path, final boolean is_staged)
    {
        diff_path = path;
        diff_staged = is_staged;
        diff.show(git -> is_staged ? git.getStagedDiff(path) : git.getUnstagedDiff(path));
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
        abort.setVisible(merging);
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
