package org.phoebus.applications.git;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

import org.csstudio.display.builder.model.DisplayModel;
import org.phoebus.applications.git.BobDiff.Change;
import org.phoebus.applications.git.WidgetHistory.Entry;
import org.phoebus.framework.jobs.JobManager;
import org.phoebus.ui.dialog.ExceptionDetailsErrorDialog;
import org.phoebus.ui.docking.DockItem;
import org.phoebus.ui.docking.DockPane;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Orientation;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.SplitPane;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.layout.BorderPane;

/** Dock tab with the history of one widget: Commits that changed it and the property changes */
@SuppressWarnings("nls")
class WidgetHistoryView
{
    private static final DateTimeFormatter DATE_FORMAT =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());

    private final TableView<Entry> history = new TableView<>();
    private final ListView<String> changes = new ListView<>();
    private final Label status = new Label(Messages.Loading);

    /** @param model Display model
     *  @param key Widget key
     *  @param name Description of the widget
     *  @param current Current content of the model, to show unsaved changes
     */
    WidgetHistoryView(final DisplayModel model, final String key, final String name, final byte[] current)
    {
        history.getColumns().add(TableHelper.createColumn(Messages.ColDate, 130,
                                 e -> e.commit() == null ? "" : DATE_FORMAT.format(e.commit().date())));
        history.getColumns().add(TableHelper.createColumn(Messages.ColAuthor, 130,
                                 e -> e.commit() == null ? "" : e.commit().author()));
        history.getColumns().add(TableHelper.createColumn(Messages.ColMessage, 400,
                                 e -> e.commit() == null ? Messages.UncommittedChanges : e.commit().message()));
        history.getColumns().add(TableHelper.createColumn(Messages.ColId, 70,
                                 e -> e.commit() == null ? "" : e.commit().id().substring(0, 7)));
        history.getColumns().add(TableHelper.createColumn(Messages.ColChanges, 300,
                                 e -> e.changes().stream()
                                                 .map(c -> c.property() == null ? c.kind().name() : c.property())
                                                 .distinct()
                                                 .reduce((a, b) -> a + ", " + b)
                                                 .orElse("")));
        for (TableColumn<Entry, ?> col : history.getColumns())
            col.setSortable(false);
        history.setPlaceholder(status);
        history.getSelectionModel().selectedItemProperty().addListener((p, o, entry) ->
        {
            if (entry == null)
                changes.getItems().clear();
            else
                changes.getItems().setAll(entry.changes().stream().map(BobDiff::format).toList());
        });
        changes.setStyle("-fx-font-family: monospace;");

        final SplitPane split = new SplitPane(history, changes);
        split.setOrientation(Orientation.VERTICAL);
        split.setDividerPositions(0.6);
        final Label title = new Label(name);
        title.setStyle("-fx-font-weight: bold;");
        title.setPadding(new Insets(5));
        final BorderPane layout = new BorderPane(split);
        layout.setTop(title);

        final DockItem tab = new DockItem(name + " - " + Messages.WidgetHistoryTitle);
        tab.setContent(layout);
        DockPane.getActiveDockPane().addTab(tab);
        tab.select();

        JobManager.schedule(Messages.GitWidgetHistory, monitor ->
        {
            try
            {
                final GitEditorSupport.DisplayFile display = GitEditorSupport.open(model);
                final List<Entry> entries;
                try
                {
                    entries = WidgetHistory.compute(display.git(), display.path(), key, current, WidgetHistory.MAX_VERSIONS);
                }
                finally
                {
                    display.git().close();
                }
                Platform.runLater(() ->
                {
                    status.setText(Messages.NoWidgetHistory);
                    history.getItems().setAll(entries);
                    if (! entries.isEmpty())
                        history.getSelectionModel().select(0);
                });
            }
            catch (Exception ex)
            {
                Platform.runLater(() -> status.setText(ex.getMessage()));
                ExceptionDetailsErrorDialog.openError(Messages.GitWidgetHistory, ex.getMessage(), ex);
            }
        });
    }

    /** @return Changes shown for the selected commit */
    List<Change> getSelectedChanges()
    {
        final Entry entry = history.getSelectionModel().getSelectedItem();
        return entry == null ? List.of() : entry.changes();
    }
}
