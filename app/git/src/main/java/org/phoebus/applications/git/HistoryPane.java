package org.phoebus.applications.git;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

import org.phoebus.applications.git.GitRepository.CommitInfo;

import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.geometry.Insets;
import javafx.geometry.Orientation;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.SplitPane;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.layout.BorderPane;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.HBox;

/** Commit history with graph, and diff of the selected commit */
@SuppressWarnings("nls")
class HistoryPane extends BorderPane
{
    private static final DateTimeFormatter DATE_FORMAT =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());

    /** Actions triggered from the history */
    interface Actions
    {
        /** @param all Show all branches, or only the current one? */
        void showAllBranches(boolean all);
        /** @param commit Commit id to tag */
        void createTag(String commit);
        /** @param commit Commit id where the branch starts */
        void createBranch(String commit);
        void cherryPick(CommitInfo commit);
        void revert(CommitInfo commit);
    }

    private final TableView<CommitInfo> history = new TableView<>();
    private final CheckBox all_branches = new CheckBox(Messages.AllBranches);
    private final DiffPane diff;

    /** Full name of checked out branch, used to highlight its label */
    private String current_branch = null;

    /** Width of the graph area */
    private int graph_width = 0;

    /** @param repo Provides the current repository
     *  @param on_error Error handler
     *  @param all Show all branches initially?
     *  @param actions Actions
     */
    HistoryPane(final Supplier<GitRepository> repo, final Consumer<Exception> on_error,
                final boolean all, final Actions actions)
    {
        diff = new DiffPane(repo, on_error);

        final TableColumn<CommitInfo, CommitInfo> description = new TableColumn<>(Messages.ColDescription);
        description.setPrefWidth(600);
        description.setSortable(false);
        description.setCellValueFactory(cell -> new ReadOnlyObjectWrapper<>(cell.getValue()));
        description.setCellFactory(col -> new CommitGraphCell(() -> current_branch, () -> graph_width));
        history.getColumns().add(description);
        history.getColumns().add(TableHelper.createColumn(Messages.ColDate, 130, c -> DATE_FORMAT.format(c.date())));
        history.getColumns().add(TableHelper.createColumn(Messages.ColAuthor, 130, c -> c.author()));
        history.getColumns().add(TableHelper.createColumn(Messages.ColId, 70, c -> c.id().substring(0, 7)));
        // Order is defined by the graph, sorting would break it
        for (TableColumn<CommitInfo, ?> col : history.getColumns())
            col.setSortable(false);
        history.setFixedCellSize(24);
        history.setContextMenu(createContextMenu(actions));
        history.getSelectionModel().selectedItemProperty().addListener((prop, old, commit) ->
        {
            if (commit != null)
                diff.show(git -> git.getCommitDiff(commit.id()));
        });

        all_branches.setSelected(all);
        all_branches.setOnAction(event -> actions.showAllBranches(all_branches.isSelected()));
        final HBox options = new HBox(5, all_branches);
        options.setPadding(new Insets(5));

        final SplitPane split = new SplitPane(history, diff);
        split.setOrientation(Orientation.VERTICAL);
        split.setDividerPositions(0.55);

        setTop(options);
        setCenter(split);
    }

    private ContextMenu createContextMenu(final Actions actions)
    {
        final MenuItem tag = new MenuItem(Messages.CreateTag);
        tag.setOnAction(event -> actions.createTag(getSelected().id()));
        final MenuItem branch = new MenuItem(Messages.CreateBranchHere);
        branch.setOnAction(event -> actions.createBranch(getSelected().id()));
        final MenuItem cherry_pick = new MenuItem(Messages.CherryPick);
        cherry_pick.setOnAction(event -> actions.cherryPick(getSelected()));
        final MenuItem revert = new MenuItem(Messages.Revert);
        revert.setOnAction(event -> actions.revert(getSelected()));
        final MenuItem copy = new MenuItem(Messages.CopyId);
        copy.setOnAction(event ->
        {
            final ClipboardContent content = new ClipboardContent();
            content.putString(getSelected().id());
            Clipboard.getSystemClipboard().setContent(content);
        });
        final ContextMenu menu = new ContextMenu(tag, branch, new SeparatorMenuItem(),
                                                 cherry_pick, revert, new SeparatorMenuItem(), copy);
        menu.setOnShowing(event ->
        {
            final boolean none = getSelected() == null;
            menu.getItems().forEach(item -> item.setDisable(none));
        });
        return menu;
    }

    private CommitInfo getSelected()
    {
        return history.getSelectionModel().getSelectedItem();
    }

    /** @param commits Commits to show
     *  @param current_branch Full name of the checked out branch, e.g. "refs/heads/master", or <code>null</code>
     */
    void setCommits(final List<CommitInfo> commits, final String current_branch)
    {
        this.current_branch = current_branch;
        // Lanes are 14 pixels wide (JGit AbstractPlotRenderer), plus space for the dot.
        // Very wide graphs are not padded, their messages simply start later
        final int lanes = commits.stream()
                                 .mapToInt(c -> c.plot().getLane() == null ? 0 : c.plot().getLane().getPosition() + 1)
                                 .max().orElse(1);
        graph_width = 14 * Math.min(lanes, 12) + 16;
        diff.clear();
        history.getItems().setAll(commits);
    }

    /** @param all Show all branches? Updates the check box, caller needs to refresh */
    void setAllBranches(final boolean all)
    {
        all_branches.setSelected(all);
    }

    /** Select a commit, or only show its diff if it is not in the list
     *  @param id Full commit id
     */
    void showCommit(final String id)
    {
        for (CommitInfo commit : history.getItems())
            if (commit.id().equals(id))
            {
                // Clear first so that the diff is also shown when already selected
                history.getSelectionModel().clearSelection();
                history.getSelectionModel().select(commit);
                history.scrollTo(commit);
                return;
            }
        history.getSelectionModel().clearSelection();
        diff.show(git -> git.getCommitDiff(id));
    }
}
