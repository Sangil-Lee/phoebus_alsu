package org.phoebus.applications.git;

import java.io.File;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Supplier;

import org.phoebus.applications.git.GitRepository.BlameLine;
import org.phoebus.applications.git.GitRepository.FileVersion;
import org.phoebus.framework.jobs.JobManager;
import org.phoebus.ui.docking.DockItem;
import org.phoebus.ui.docking.DockPane;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Orientation;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.SplitPane;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableRow;
import javafx.scene.control.TableView;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;

/** Dock tab with the history and 'blame' of one file */
@SuppressWarnings("nls")
class FileHistoryView
{
    private static final DateTimeFormatter DATE_FORMAT =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());

    /** Maximum number of commits in the file history */
    private static final int MAX_HISTORY = 1000;

    /** Alternating backgrounds for blocks of lines from the same commit */
    private static final String[] BLAME_BACKGROUND = { "#ffffff", "#f1f5fb" };

    private final Supplier<GitRepository> repo;
    private final String path;
    private final Consumer<Exception> on_error;

    private final BorderPane layout = new BorderPane();
    private final TableView<FileVersion> history = new TableView<>();
    private final DiffPane diff;
    private final SplitPane history_pane;
    private final TableView<BlameLine> blame = new TableView<>();
    private final ToggleButton show_history = new ToggleButton(Messages.History);
    private final ToggleButton show_blame = new ToggleButton(Messages.Blame);
    private final Label status = new Label();

    /** Has blame been read? */
    private boolean have_blame = false;

    /** For each blame line 0 or 1, changing whenever the commit changes from one line to the next */
    private int[] blocks = new int[0];

    /** @param repo Provides the current repository
     *  @param path File path relative to the work tree
     *  @param on_error Error handler
     */
    FileHistoryView(final Supplier<GitRepository> repo, final String path, final Consumer<Exception> on_error)
    {
        this(repo, path, on_error, null);
    }

    /** @param repo Provides the repository
     *  @param path File path relative to the work tree
     *  @param on_error Error handler
     *  @param owned Repository that this view owns and closes when the tab is closed, or <code>null</code>
     */
    FileHistoryView(final Supplier<GitRepository> repo, final String path, final Consumer<Exception> on_error,
                    final GitRepository owned)
    {
        this.repo = repo;
        this.path = path;
        this.on_error = on_error;
        diff = new DiffPane(repo, on_error);

        history.getColumns().add(TableHelper.createColumn(Messages.ColDate, 130, v -> DATE_FORMAT.format(v.commit().date())));
        history.getColumns().add(TableHelper.createColumn(Messages.ColAuthor, 130, v -> v.commit().author()));
        history.getColumns().add(TableHelper.createColumn(Messages.ColMessage, 400, v -> v.commit().message()));
        history.getColumns().add(TableHelper.createColumn(Messages.ColId, 70, v -> v.commit().id().substring(0, 7)));
        // Path only differs from the current path before a rename
        history.getColumns().add(TableHelper.createColumn(Messages.ColPath, 250, v -> v.path().equals(path) ? "" : v.path()));
        history.getSelectionModel().selectedItemProperty().addListener((prop, old, version) ->
        {
            if (version != null)
                diff.show(git -> git.getCommitFileDiff(version.commit().id(), version.path()), null,
                          BobDiff.supports(version.path())
                          ? git -> git.getCommitFileVersions(version.commit().id(), version.path())
                          : null);
        });
        history_pane = new SplitPane(history, diff);
        history_pane.setOrientation(Orientation.VERTICAL);
        history_pane.setDividerPositions(0.4);

        configureBlame();

        final ToggleGroup mode = new ToggleGroup();
        show_history.setToggleGroup(mode);
        show_blame.setToggleGroup(mode);
        show_history.setSelected(true);
        mode.selectedToggleProperty().addListener((prop, old, selected) ->
        {
            // Keep one mode selected
            if (selected == null)
                old.setSelected(true);
            else
                showMode();
        });
        final Button refresh = new Button(Messages.Refresh);
        refresh.setOnAction(event -> load());

        final Label file = new Label(path);
        file.setStyle("-fx-font-weight: bold;");
        final Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        final HBox top = new HBox(5, show_history, show_blame, file, spacer, status, refresh);
        top.setAlignment(Pos.CENTER_LEFT);
        top.setPadding(new Insets(5));
        layout.setTop(top);
        showMode();

        final DockItem tab = new DockItem(new File(path).getName() + " - " + Messages.FileHistory);
        tab.setContent(layout);
        DockPane.getActiveDockPane().addTab(tab);
        tab.select();
        if (owned != null)
            tab.addClosedNotification(owned::close);

        load();
    }

    private void configureBlame()
    {
        final String mono = "-fx-font-family: monospace;";
        final TableColumn<BlameLine, String> line = TableHelper.createColumn("#", 50, l -> Integer.toString(l.number()));
        line.setStyle("-fx-alignment: center-right;" + mono);
        blame.getColumns().add(line);
        blame.getColumns().add(TableHelper.createColumn(Messages.ColId, 100,
                               l -> l.commit() == null ? Messages.NotCommitted : l.commit().substring(0, 7)));
        blame.getColumns().add(TableHelper.createColumn(Messages.ColAuthor, 120, l -> l.author()));
        blame.getColumns().add(TableHelper.createColumn(Messages.ColDate, 130,
                               l -> l.date() == null ? "" : DATE_FORMAT.format(l.date())));
        final TableColumn<BlameLine, String> text = TableHelper.createColumn(Messages.ColText, 600, l -> l.text());
        text.setStyle(mono);
        blame.getColumns().add(text);
        for (TableColumn<BlameLine, ?> col : blame.getColumns())
            col.setSortable(false);

        // Shade blocks of lines from the same commit, mark uncommitted lines.
        // Changing the 'inner background' keeps the selection highlight
        blame.setRowFactory(view -> new TableRow<>()
        {
            @Override
            protected void updateItem(final BlameLine item, final boolean empty)
            {
                super.updateItem(item, empty);
                final int index = getIndex();
                if (empty  ||  item == null  ||  index < 0  ||  index >= blocks.length)
                    setStyle(null);
                else
                {
                    final String color = item.commit() == null ? "#fff6d5" : BLAME_BACKGROUND[blocks[index]];
                    setStyle("-fx-control-inner-background: " + color + "; -fx-control-inner-background-alt: " + color + ";");
                }
            }
        });

        // Double-click shows the commit in the history
        blame.setOnMouseClicked(event ->
        {
            final BlameLine selected = blame.getSelectionModel().getSelectedItem();
            if (event.getButton() == MouseButton.PRIMARY  &&  event.getClickCount() == 2  &&
                selected != null  &&  selected.commit() != null)
                showCommit(selected.commit());
        });
    }

    /** @param lines Blame lines to show */
    private void setBlame(final List<BlameLine> lines)
    {
        blocks = new int[lines.size()];
        for (int i=1; i<lines.size(); ++i)
            blocks[i] = Objects.equals(lines.get(i).commit(), lines.get(i-1).commit()) ? blocks[i-1] : 1 - blocks[i-1];
        blame.getItems().setAll(lines);
    }

    private void showMode()
    {
        if (show_blame.isSelected())
        {
            layout.setCenter(blame);
            if (! have_blame)
                loadBlame();
        }
        else
            layout.setCenter(history_pane);
    }

    /** Select commit in the history
     *  @param id Commit id
     */
    private void showCommit(final String id)
    {
        show_history.setSelected(true);
        for (FileVersion version : history.getItems())
            if (version.commit().id().equals(id))
            {
                history.getSelectionModel().select(version);
                history.scrollTo(version);
                return;
            }
    }

    /** Read history, and blame if shown */
    private void load()
    {
        final GitRepository current = repo.get();
        if (current == null)
            return;
        JobManager.schedule(Messages.FileHistory, monitor ->
        {
            try
            {
                final List<FileVersion> versions = current.getFileHistory(path, MAX_HISTORY);
                Platform.runLater(() ->
                {
                    history.getItems().setAll(versions);
                    status.setText(String.format(Messages.Commits, versions.size()));
                    if (! versions.isEmpty())
                        history.getSelectionModel().select(0);
                });
            }
            catch (Exception ex)
            {
                on_error.accept(ex);
            }
        });
        have_blame = false;
        if (show_blame.isSelected())
            loadBlame();
    }

    private void loadBlame()
    {
        final GitRepository current = repo.get();
        if (current == null)
            return;
        have_blame = true;
        JobManager.schedule(Messages.Blame, monitor ->
        {
            try
            {
                final List<BlameLine> lines = current.blame(path);
                Platform.runLater(() -> setBlame(lines));
            }
            catch (Exception ex)
            {
                Platform.runLater(() -> setBlame(List.of()));
                on_error.accept(ex);
            }
        });
    }
}
