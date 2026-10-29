package org.phoebus.applications.git;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

import org.phoebus.applications.git.GitRepository.CommitFile;
import org.phoebus.applications.git.GitRepository.CommitInfo;

import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.geometry.Orientation;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SelectionMode;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.SplitPane;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;

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
        /** @param commits Commits to cherry-pick, newest first */
        void cherryPick(List<CommitInfo> commits);
        /** @param commits Commits to revert, newest first */
        void revert(List<CommitInfo> commits);
        /** Create logbook entry for the commits, newest first */
        void logbook(List<CommitInfo> commits);
        /** Reset current branch to commit, after asking for the mode */
        void reset(CommitInfo commit);
        /** Interactive rebase of the commits after this one */
        void interactiveRebase(CommitInfo commit);
        /** Save the commits as a patch file */
        void createPatch(List<CommitInfo> commits);
        /** Read more commits */
        void loadMore();
        /** Show the uncommitted changes in the file status */
        void showFileStatus();
        /** @param path File to show in the file history */
        void showFileHistory(String path);
        /** @param text Text to search in message, author, id; empty to show all commits */
        void search(String text);
        /** @param editor Open display in editor, otherwise default application */
        void openFile(String path, boolean editor);
    }

    /** Id of the 'Uncommitted changes' row */
    static final String UNCOMMITTED = "";

    /** @return Is this the 'Uncommitted changes' row, not an actual commit? */
    static boolean isUncommitted(final CommitInfo commit)
    {
        return commit != null  &&  UNCOMMITTED.equals(commit.id());
    }

    private final GitActions git_actions;
    private final TableView<CommitInfo> history = new TableView<>();
    private final CheckBox all_branches = new CheckBox(Messages.AllBranches);
    private final CommitFilesPane details;
    private final Label count = new Label();
    private final TextField search = new TextField();
    private final Button load_more = new Button(Messages.LoadMore);

    /** Changed files, shown as 'Uncommitted changes' */
    private List<CommitFile> uncommitted = List.of();

    /** Full name of checked out branch, used to highlight its label */
    private String current_branch = null;

    /** Width of the graph area */
    private int graph_width = 0;

    /** Commits, their refs and the uncommitted files that are shown, to detect changes */
    private List<String> signature = List.of();

    /** @param repo Provides the current repository
     *  @param on_error Error handler
     *  @param all Show all branches initially?
     *  @param actions Actions
     */
    HistoryPane(final Supplier<GitRepository> repo, final Consumer<Exception> on_error,
                final boolean all, final Actions actions, final GitActions git_actions)
    {
        this.git_actions = git_actions;
        details = new CommitFilesPane(repo, on_error, actions::showFileHistory, actions::openFile, git_actions);

        final TableColumn<CommitInfo, CommitInfo> description = new TableColumn<>(Messages.ColDescription);
        description.setPrefWidth(600);
        description.setSortable(false);
        description.setCellValueFactory(cell -> new ReadOnlyObjectWrapper<>(cell.getValue()));
        description.setCellFactory(col -> new CommitGraphCell(() -> current_branch, () -> graph_width));
        history.getColumns().add(description);
        history.getColumns().add(TableHelper.createColumn(Messages.ColDate, 130, c -> c.date() == null ? "" : DATE_FORMAT.format(c.date())));
        history.getColumns().add(TableHelper.createColumn(Messages.ColAuthor, 130, c -> c.author()));
        history.getColumns().add(TableHelper.createColumn(Messages.ColId, 70, c -> isUncommitted(c) ? "" : c.id().substring(0, 7)));
        // Order is defined by the graph, sorting would break it
        for (TableColumn<CommitInfo, ?> col : history.getColumns())
            col.setSortable(false);
        history.setFixedCellSize(24);
        // Several commits for cherry-pick, patch, logbook, comparing two
        history.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
        history.setContextMenu(createContextMenu(actions));
        history.getSelectionModel().selectedItemProperty().addListener((prop, old, commit) ->
        {
            if (isUncommitted(commit))
                details.showUncommitted(uncommitted);
            else if (commit != null)
                details.showCommit(commit.id());
        });
        // Double-click on 'Uncommitted changes' opens the file status to stage and commit
        history.setOnMouseClicked(event ->
        {
            if (event.getButton() == MouseButton.PRIMARY  &&  event.getClickCount() == 2  &&
                isUncommitted(history.getSelectionModel().getSelectedItem()))
                actions.showFileStatus();
        });

        all_branches.setSelected(all);
        all_branches.setOnAction(event -> actions.showAllBranches(all_branches.isSelected()));
        load_more.setOnAction(event -> actions.loadMore());
        search.setPromptText(Messages.SearchPrompt);
        search.setPrefColumnCount(25);
        search.setOnAction(event -> actions.search(search.getText().trim()));
        final Button clear_search = new Button("\u2715");
        clear_search.setOnAction(event ->
        {
            search.clear();
            actions.search("");
        });
        clear_search.visibleProperty().bind(search.textProperty().isNotEmpty());
        final Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        final HBox options = new HBox(5, all_branches, new Label(Messages.Search), search, clear_search, spacer, count, load_more);
        options.setAlignment(Pos.CENTER_LEFT);
        options.setPadding(new Insets(5));

        final SplitPane split = new SplitPane(history, details);
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
        cherry_pick.setOnAction(event -> actions.cherryPick(getSelectedCommits()));
        final MenuItem revert = new MenuItem(Messages.Revert);
        revert.setOnAction(event -> actions.revert(getSelectedCommits()));
        final MenuItem reset = new MenuItem(Messages.Reset);
        reset.setOnAction(event -> actions.reset(getSelected()));
        final MenuItem rebase = new MenuItem(Messages.InteractiveRebaseMenu);
        rebase.setOnAction(event -> actions.interactiveRebase(getSelected()));
        final MenuItem logbook = new MenuItem(Messages.Logbook);
        logbook.setOnAction(event -> actions.logbook(getSelectedCommits()));
        final MenuItem patch = new MenuItem(Messages.CreatePatch);
        patch.setOnAction(event -> actions.createPatch(getSelectedCommits()));
        final MenuItem copy = new MenuItem(Messages.CopyId);
        copy.setOnAction(event ->
        {
            final ClipboardContent content = new ClipboardContent();
            content.putString(String.join("\n", getSelectedCommits().stream().map(CommitInfo::id).toList()));
            Clipboard.getSystemClipboard().setContent(content);
        });
        // Two selected commits: Compare older with newer
        final MenuItem compare_two = new MenuItem(Messages.CompareCommits);
        compare_two.setOnAction(event ->
        {
            final List<CommitInfo> selected = getSelectedCommits();
            git_actions.compare(toRevision(selected.get(1)), toRevision(selected.get(0)), null);
        });
        final MenuItem compare_work = new MenuItem(Messages.CompareWithWorkTree);
        compare_work.setOnAction(event -> git_actions.compare(toRevision(getSelected()), Revision.WORK_TREE, null));
        final MenuItem compare_other = new MenuItem(Messages.CompareWithOther);
        compare_other.setOnAction(event ->
        {
            final Revision.Commit selected = toRevision(getSelected());
            git_actions.pickRevision(Messages.CompareWithOther, other -> git_actions.compare(other, selected, null));
        });
        final ContextMenu menu = new ContextMenu(tag, branch, new SeparatorMenuItem(),
                                                 cherry_pick, revert, reset, rebase, new SeparatorMenuItem(),
                                                 compare_work, compare_other, compare_two, new SeparatorMenuItem(),
                                                 patch, logbook, copy);
        // Operations on one commit, or on any number of them
        final List<MenuItem> single = List.of(tag, branch, reset, rebase, compare_work, compare_other);
        menu.setOnShowing(event ->
        {
            // Commit operations need an actual commit
            final int count = getSelectedCommits().size();
            menu.getItems().forEach(item -> item.setDisable(count == 0));
            single.forEach(item -> item.setDisable(count != 1));
            compare_two.setDisable(count != 2);
        });
        return menu;
    }

    /** @param commit Commit
     *  @return Revision for the commit
     */
    private static Revision.Commit toRevision(final CommitInfo commit)
    {
        return new Revision.Commit(commit.id(), commit.id().substring(0, 7));
    }

    private CommitInfo getSelected()
    {
        final List<CommitInfo> selected = getSelectedCommits();
        return selected.isEmpty() ? null : selected.get(0);
    }

    /** @return Selected commits, newest first, without the 'uncommitted changes' row */
    List<CommitInfo> getSelectedCommits()
    {
        return history.getSelectionModel().getSelectedIndices().stream()
                      .sorted()
                      .map(history.getItems()::get)
                      .filter(commit -> ! isUncommitted(commit))
                      .toList();
    }

    /** @param commits Commits to show
     *  @param current_branch Full name of the checked out branch, e.g. "refs/heads/master", or <code>null</code>
     *  @param requested Number of commits that were requested; if that many were found, there may be more
     *  @param changed Files with uncommitted changes, shown as first row
     */
    void setCommits(final List<CommitInfo> commits, final String current_branch,
                    final int requested, final List<CommitFile> changed)
    {
        count.setText(String.format(Messages.Commits, commits.size()));
        load_more.setVisible(commits.size() >= requested);

        // When nothing changed, keep the table and its selection.
        // Uncommitted files may still have new content, so their diff is updated
        final List<String> new_signature = getSignature(commits, current_branch, changed);
        if (new_signature.equals(signature))
        {
            if (isUncommitted(history.getSelectionModel().getSelectedItem()))
                details.showUncommitted(changed);
            return;
        }
        signature = new_signature;

        this.current_branch = current_branch;
        this.uncommitted = changed;
        // Lanes are 14 pixels wide (JGit AbstractPlotRenderer), plus space for the dot.
        // Very wide graphs are not padded, their messages simply start later
        final int lanes = commits.stream()
                                 .mapToInt(c -> c.plot().getLane() == null ? 0 : c.plot().getLane().getPosition() + 1)
                                 .max().orElse(1);
        graph_width = 14 * Math.min(lanes, 12) + 16;

        final List<CommitInfo> rows = new ArrayList<>(commits.size() + 1);
        if (! changed.isEmpty())
            rows.add(new CommitInfo(UNCOMMITTED, Messages.UncommittedChanges + " (" + changed.size() + ")",
                                    "", null, null));
        rows.addAll(commits);

        // Keep selection of the same commits, or of the 'uncommitted' row
        final CommitInfo focus = history.getSelectionModel().getSelectedItem();
        final List<String> selected = history.getSelectionModel().getSelectedItems().stream().map(CommitInfo::id).toList();
        details.clear();
        history.getItems().setAll(rows);
        for (int i=0; i<rows.size(); ++i)
            if (selected.contains(rows.get(i).id())  &&  (focus == null  ||  ! rows.get(i).id().equals(focus.id())))
                history.getSelectionModel().select(i);
        // Select the focused row last, its details are shown
        if (focus != null)
            for (int i=0; i<rows.size(); ++i)
                if (rows.get(i).id().equals(focus.id()))
                    history.getSelectionModel().select(i);
    }

    /** @return Description of what's shown: Commits with refs, current branch, uncommitted files */
    private static List<String> getSignature(final List<CommitInfo> commits, final String current_branch,
                                             final List<CommitFile> changed)
    {
        final List<String> result = new ArrayList<>(commits.size() + changed.size() + 1);
        result.add(String.valueOf(current_branch));
        for (CommitInfo commit : commits)
        {
            final StringBuilder entry = new StringBuilder(commit.id());
            for (int i=0; i<commit.plot().getRefCount(); ++i)
                entry.append(' ').append(commit.plot().getRef(i).getName());
            result.add(entry.toString());
        }
        for (CommitFile file : changed)
            result.add(file.type() + ":" + file.path());
        return result;
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
        details.showCommit(id);
    }
}
