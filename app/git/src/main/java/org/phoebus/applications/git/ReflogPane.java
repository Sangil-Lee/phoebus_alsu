package org.phoebus.applications.git;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

import org.phoebus.applications.git.GitRepository.ReflogInfo;

import javafx.geometry.Orientation;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SplitPane;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.BorderPane;

/** Reflog of HEAD: Where HEAD pointed after each commit, checkout, reset, rebase, ...
 *
 *  <p>Allows finding commits that are no longer on any branch,
 *  for example after a reset or amend.
 */
@SuppressWarnings("nls")
class ReflogPane extends BorderPane
{
    private static final DateTimeFormatter DATE_FORMAT =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());

    /** Actions on a reflog entry */
    interface Actions
    {
        /** @param commit Commit id where the branch starts */
        void createBranch(String commit);
        /** Reset current branch to the entry's commit, after asking for the mode */
        void reset(ReflogInfo entry);
    }

    private final TableView<ReflogInfo> table = new TableView<>();
    private final CommitFilesPane details;

    /** @param repo Provides the current repository
     *  @param on_error Error handler
     *  @param actions Actions
     *  @param git_actions Compare, restore, ...
     *  @param on_file_history Show history of a file
     */
    ReflogPane(final Supplier<GitRepository> repo, final Consumer<Exception> on_error,
               final Actions actions, final GitActions git_actions, final Consumer<String> on_file_history)
    {
        details = new CommitFilesPane(repo, on_error, on_file_history,
                                      (path, editor) -> { /* Old versions are opened via 'Open This Version' */ },
                                      git_actions);
        table.getColumns().add(TableHelper.createColumn(Messages.ColDate, 150, e -> DATE_FORMAT.format(e.date())));
        table.getColumns().add(TableHelper.createColumn(Messages.ColAuthor, 120, e -> e.who()));
        table.getColumns().add(TableHelper.createColumn(Messages.ColAction, 500, e -> e.comment()));
        table.getColumns().add(TableHelper.createColumn(Messages.ColId, 70, e -> e.id().substring(0, 7)));
        for (TableColumn<ReflogInfo, ?> col : table.getColumns())
            col.setSortable(false);
        table.setPlaceholder(new Label(Messages.NoReflog));
        table.getSelectionModel().selectedItemProperty().addListener((p, o, entry) ->
        {
            if (entry != null)
                details.showCommit(entry.id());
        });

        final MenuItem branch = new MenuItem(Messages.CreateBranchHere);
        branch.setOnAction(event -> actions.createBranch(getSelected().id()));
        final MenuItem reset = new MenuItem(Messages.Reset);
        reset.setOnAction(event -> actions.reset(getSelected()));
        final MenuItem compare = new MenuItem(Messages.CompareWithWorkTree);
        compare.setOnAction(event -> git_actions.compare(toRevision(getSelected()), Revision.WORK_TREE, null));
        final MenuItem copy = new MenuItem(Messages.CopyId);
        copy.setOnAction(event ->
        {
            final ClipboardContent content = new ClipboardContent();
            content.putString(getSelected().id());
            Clipboard.getSystemClipboard().setContent(content);
        });
        final ContextMenu menu = new ContextMenu(branch, reset, compare, copy);
        menu.setOnShowing(event -> menu.getItems().forEach(item -> item.setDisable(getSelected() == null)));
        table.setContextMenu(menu);

        final SplitPane split = new SplitPane(table, details);
        split.setOrientation(Orientation.VERTICAL);
        split.setDividerPositions(0.55);
        setCenter(split);
    }

    private static Revision.Commit toRevision(final ReflogInfo entry)
    {
        return new Revision.Commit(entry.id(), entry.id().substring(0, 7));
    }

    private ReflogInfo getSelected()
    {
        return table.getSelectionModel().getSelectedItem();
    }

    /** @param entries Reflog entries, newest first */
    void setEntries(final List<ReflogInfo> entries)
    {
        final ReflogInfo selected = getSelected();
        if (table.getItems().equals(entries))
            return;
        table.getItems().setAll(entries);
        if (selected != null)
            table.getItems().stream().filter(selected::equals).findFirst().ifPresent(table.getSelectionModel()::select);
    }
}
