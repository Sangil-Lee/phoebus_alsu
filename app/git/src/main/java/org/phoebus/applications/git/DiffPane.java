package org.phoebus.applications.git;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.Supplier;

import org.phoebus.applications.git.SideBySideDiff.Row;
import org.phoebus.framework.jobs.JobManager;

import javafx.application.Platform;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.value.ChangeListener;
import javafx.beans.value.WeakChangeListener;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.ListView;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SelectionMode;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;

/** Shows a diff that is computed in the background, unified or side by side */
@SuppressWarnings("nls")
class DiffPane extends BorderPane
{
    /** Repository query that returns diff text */
    @FunctionalInterface
    interface DiffQuery
    {
        String get(GitRepository git) throws Exception;
    }

    /** Actions offered for each hunk of a single-file diff */
    interface HunkActions
    {
        /** @return Button labels, one per action */
        List<String> getLabels();

        /** @param action Index of the action
         *  @param hunk Index of the hunk in the diff
         */
        void run(int action, int hunk);

        /** @return Menu labels for actions on selected lines, empty if none */
        default List<String> getLineLabels()
        {
            return List.of();
        }

        /** @param action Index of the action
         *  @param lines Selected lines: Index of line in its hunk, 0 for the line after the '@@' header, by hunk index
         */
        default void runLines(final int action, final Map<Integer, Set<Integer>> lines)
        {
            // No line actions
        }
    }

    /** Side-by-side mode, shared by all diff panes */
    private static final BooleanProperty side_by_side = new SimpleBooleanProperty(false);

    /** Ignore white space, shared by all diff panes */
    private static final BooleanProperty ignore_whitespace = new SimpleBooleanProperty(false);

    /** Display files by widget, shared by all diff panes */
    private static final BooleanProperty widget_mode = new SimpleBooleanProperty(false);

    /** Query for old and new content of a display file */
    @FunctionalInterface
    interface VersionsQuery
    {
        GitRepository.Versions get(GitRepository git) throws Exception;
    }

    private final Supplier<GitRepository> repo;
    private final Consumer<Exception> on_error;
    private final ListView<String> unified = new ListView<>();
    private final ListView<Row> split = new ListView<>();
    private final ListView<String> widgets = new ListView<>();
    private final CheckBox widget_toggle = new CheckBox(Messages.WidgetMode);

    /** Lines of the diff that's currently shown */
    private List<String> lines = List.of();

    /** Updates this pane when the mode changes. Registered as weak listener,
     *  so the shared mode property does not keep panes of closed tabs
     */
    private final ChangeListener<Boolean> mode_listener = (p, o, n) -> showLines();

    /** Path of the display file shown by widget, <code>null</code> if not known */
    private String widget_path = null;

    /** Widget key for each line of the widget changes, <code>null</code> if line is not for a widget */
    private List<String> widget_keys = List.of();

    /** Most recent query, hunk actions and widget query, to re-compute the diff */
    private DiffQuery last_query = null;
    private HunkActions last_actions = null;
    private VersionsQuery last_widget_query = null;

    /** Re-computes the diff when the white space option changes, also weak */
    private final ChangeListener<Boolean> whitespace_listener = (p, o, n) -> recompute();

    /** Hunk actions for the diff that's shown, <code>null</code> if none */
    private volatile HunkActions hunk_actions = null;

    /** Number of the most recent request, older results are dropped */
    private final AtomicLong request = new AtomicLong();

    /** @param repo Provides the current repository
     *  @param on_error Called with errors from computing the diff
     */
    DiffPane(final Supplier<GitRepository> repo, final Consumer<Exception> on_error)
    {
        this.repo = repo;
        this.on_error = on_error;
        unified.setCellFactory(view -> new DiffLineCell(() -> hunk_actions));
        // Select lines to stage, unstage, discard them
        unified.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
        final ContextMenu line_menu = new ContextMenu();
        line_menu.setOnShowing(event ->
        {
            line_menu.getItems().clear();
            final HunkActions actions = hunk_actions;
            final Map<Integer, Set<Integer>> selected = getSelectedLines();
            if (actions == null)
                return;
            final List<String> labels = actions.getLineLabels();
            for (int i=0; i<labels.size(); ++i)
            {
                final int action = i;
                final MenuItem item = new MenuItem(labels.get(i));
                item.setDisable(selected.isEmpty());
                item.setOnAction(e -> actions.runLines(action, selected));
                line_menu.getItems().add(item);
            }
        });
        // Menu needs at least one item to show
        line_menu.getItems().add(new MenuItem());
        unified.setContextMenu(line_menu);
        split.setCellFactory(view -> new SideBySideCell());
        widgets.setCellFactory(view -> new WidgetChangeCell());
        // Double-click on a widget selects it in the display editor
        widgets.setOnMouseClicked(event ->
        {
            final int index = widgets.getSelectionModel().getSelectedIndex();
            if (event.getButton() == MouseButton.PRIMARY  &&  event.getClickCount() == 2  &&
                index >= 0  &&  index < widget_keys.size())
                selectInEditor(widget_keys.get(index));
        });

        final CheckBox toggle = new CheckBox(Messages.SideBySide);
        toggle.selectedProperty().bindBidirectional(side_by_side);
        final CheckBox whitespace = new CheckBox(Messages.IgnoreWhitespace);
        whitespace.selectedProperty().bindBidirectional(ignore_whitespace);
        widget_toggle.selectedProperty().bindBidirectional(widget_mode);
        // Only offered for display files
        widget_toggle.managedProperty().bind(widget_toggle.visibleProperty());
        widget_toggle.setVisible(false);
        final HBox options = new HBox(10, widget_toggle, whitespace, toggle);
        options.setAlignment(Pos.CENTER_RIGHT);
        options.setPadding(new Insets(2, 5, 2, 5));
        setTop(options);

        side_by_side.addListener(new WeakChangeListener<>(mode_listener));
        ignore_whitespace.addListener(new WeakChangeListener<>(whitespace_listener));
        widget_mode.addListener(new WeakChangeListener<>(whitespace_listener));
        showLines();
    }

    /** Compute the most recent diff again */
    private void recompute()
    {
        if (last_query != null)
            show(last_query, last_actions, last_widget_query, widget_path);
    }

    /** Compute diff in background and show it
     *  @param query Computes the diff
     */
    void show(final DiffQuery query)
    {
        show(query, null);
    }

    /** Compute diff of a single file in background and show it
     *  @param query Computes the diff
     *  @param actions Actions for each hunk, <code>null</code> for none
     */
    void show(final DiffQuery query, final HunkActions actions)
    {
        show(query, actions, null);
    }

    /** Compute diff of a single file in background and show it
     *  @param query Computes the diff
     *  @param actions Actions for each hunk, <code>null</code> for none
     *  @param widget_query For display files, old and new content to compare by widget, otherwise <code>null</code>
     */
    void show(final DiffQuery query, final HunkActions actions, final VersionsQuery widget_query)
    {
        show(query, actions, widget_query, null);
    }

    /** Compute diff of a single file in background and show it
     *  @param query Computes the diff
     *  @param actions Actions for each hunk, <code>null</code> for none
     *  @param widget_query For display files, old and new content to compare by widget, otherwise <code>null</code>
     *  @param widget_path Path of the display file in the work tree, to select widgets in an editor, or <code>null</code>
     */
    void show(final DiffQuery query, final HunkActions actions, final VersionsQuery widget_query, final String widget_path)
    {
        this.widget_path = widget_path;
        last_query = query;
        last_actions = actions;
        last_widget_query = widget_query;
        widget_toggle.setVisible(widget_query != null);
        final GitRepository current = repo.get();
        if (current == null)
            return;
        final boolean ignore = ignore_whitespace.get();
        final long this_request = request.incrementAndGet();
        if (widget_query != null  &&  widget_mode.get())
        {
            JobManager.schedule(Messages.Diff, monitor ->
            {
                try
                {
                    final GitRepository.Versions versions = widget_query.get(current);
                    final List<String> keys = new ArrayList<>();
                    final List<String> result = BobDiff.format(versions.old_content(), versions.new_content(), keys);
                    Platform.runLater(() ->
                    {
                        if (this_request == request.get())
                        {
                            widget_keys = keys;
                            widgets.getItems().setAll(result);
                            setCenter(widgets);
                        }
                    });
                }
                catch (Exception ex)
                {
                    on_error.accept(ex);
                }
            });
            return;
        }
        JobManager.schedule(Messages.Diff, monitor ->
        {
            try
            {
                final List<String> result;
                // White space setting and query in one step, not interrupted by other diff requests
                synchronized (current)
                {
                    current.setIgnoreWhitespace(ignore);
                    result = Arrays.asList(query.get(current).split("\\R", -1));
                }
                Platform.runLater(() ->
                {
                    if (this_request == request.get())
                    {
                        lines = result;
                        // Hunks differ when white space is ignored, offer actions only for the complete diff
                        hunk_actions = ignore ? null : actions;
                        showLines();
                    }
                });
            }
            catch (Exception ex)
            {
                on_error.accept(ex);
            }
        });
    }

    /** Select a widget in the display editor, opening the display if necessary
     *  @param key Widget key, <code>null</code> for none
     */
    private void selectInEditor(final String key)
    {
        final GitRepository current = repo.get();
        if (key == null  ||  key.isEmpty()  ||  widget_path == null  ||  current == null)
            return;
        final File file = new File(current.getWorkTree(), widget_path);
        if (GitEditorSupport.selectWidget(file, key)  ||  ! file.isFile())
            return;
        // Open in editor, then select once loaded
        OpenFiles.open(file, true);
        JobManager.schedule(Messages.OpenInEditor, monitor ->
        {
            for (int i=0; i<50; ++i)
            {
                Thread.sleep(200);
                final CompletableFuture<Boolean> selected = new CompletableFuture<>();
                Platform.runLater(() -> selected.complete(GitEditorSupport.selectWidget(file, key)));
                if (selected.get())
                    return;
            }
        });
    }

    /** @return Selected added or removed lines: Index in hunk by hunk index */
    Map<Integer, Set<Integer>> getSelectedLines()
    {
        final Map<Integer, Set<Integer>> result = new TreeMap<>();
        final List<Integer> selected = unified.getSelectionModel().getSelectedIndices();
        final List<String> shown = unified.getItems();
        int hunk = -1, header = -1;
        for (int i=0; i<shown.size(); ++i)
        {
            final String line = shown.get(i);
            if (line.startsWith("@@"))
            {
                ++hunk;
                header = i;
            }
            else if (hunk >= 0  &&  selected.contains(i)  &&
                     (line.startsWith("+")  ||  line.startsWith("-"))  &&
                     ! line.startsWith("+++")  &&  ! line.startsWith("---"))
                result.computeIfAbsent(hunk, h -> new TreeSet<>()).add(i - header - 1);
        }
        return result;
    }

    /** Clear the diff, dropping pending results */
    void clear()
    {
        last_query = null;
        last_actions = null;
        last_widget_query = null;
        widget_toggle.setVisible(false);
        hunk_actions = null;
        request.incrementAndGet();
        lines = List.of();
        showLines();
    }

    /** Show current lines in unified or side-by-side mode */
    private void showLines()
    {
        if (side_by_side.get())
        {
            split.getItems().setAll(SideBySideDiff.convert(lines));
            unified.getItems().clear();
            setCenter(split);
        }
        else
        {
            unified.getItems().setAll(lines);
            split.getItems().clear();
            setCenter(unified);
        }
    }

    /** Cell for one widget change: Added green, removed red, changed orange */
    private static class WidgetChangeCell extends ListCell<String>
    {
        @Override
        protected void updateItem(final String line, final boolean empty)
        {
            super.updateItem(line, empty);
            if (empty  ||  line == null)
            {
                setText(null);
                setStyle(null);
                return;
            }
            setText(line);
            if (line.startsWith("+"))
                setStyle("-fx-background-color: #e6ffec;");
            else if (line.startsWith("-"))
                setStyle("-fx-background-color: #ffebe9;");
            else if (line.startsWith("~"))
                setStyle("-fx-background-color: #fff4e5;");
            else
                setStyle("-fx-font-style: italic;");
        }
    }

    /** Cell for one side-by-side row: Line number and text for old and new side */
    private static class SideBySideCell extends ListCell<Row>
    {
        private static final String FONT = "-fx-font-family: monospace;";
        private static final String NUMBER = FONT + "-fx-text-fill: gray; -fx-alignment: top-right; -fx-padding: 0 4 0 0;";

        private final Label left_number = new Label(), left = new Label(),
                            right_number = new Label(), right = new Label();
        private final HBox box = new HBox(left_number, left, right_number, right);

        SideBySideCell()
        {
            for (Label number : List.of(left_number, right_number))
            {
                number.setMinWidth(45);
                number.setPrefWidth(45);
            }
            for (Label text : List.of(left, right))
            {
                text.setMaxWidth(Double.MAX_VALUE);
                // Both sides get half of the width, long lines wrap
                text.prefWidthProperty().bind(widthProperty().subtract(110).divide(2));
                text.setMinWidth(0);
                text.setWrapText(true);
                text.setAlignment(Pos.TOP_LEFT);
                HBox.setHgrow(text, Priority.ALWAYS);
            }
            setPadding(Insets.EMPTY);
        }

        @Override
        protected void updateItem(final Row row, final boolean empty)
        {
            super.updateItem(row, empty);
            setText(null);
            if (empty  ||  row == null)
            {
                setGraphic(null);
                setStyle(null);
                return;
            }
            switch (row.kind())
            {
            case HEADER:
            case HUNK:
                // Show as one line across both sides
                setGraphic(null);
                setText(row.left());
                setStyle(row.kind() == SideBySideDiff.Kind.HUNK
                         ? FONT + "-fx-text-fill: #0050a0; -fx-background-color: #eef4fb;"
                         : FONT + (row.left().startsWith("diff ") ? "-fx-font-weight: bold;" : ""));
                return;
            default:
                setStyle(null);
                setGraphic(box);
            }
            final boolean change = row.kind() == SideBySideDiff.Kind.CHANGE;
            update(left_number, left, row.left(), row.left_number(), change ? "#ffebe9" : null);
            update(right_number, right, row.right(), row.right_number(), change ? "#e6ffec" : null);
        }

        /** @param color Background for a changed line, <code>null</code> for context */
        private static void update(final Label number, final Label text, final String line,
                                   final int line_number, final String color)
        {
            number.setStyle(NUMBER);
            if (line == null)
            {
                // No line on this side, for example an added line on the right
                number.setText("");
                text.setText("");
                text.setStyle(FONT + "-fx-background-color: #f4f4f4;");
            }
            else
            {
                number.setText(Integer.toString(line_number));
                text.setText(line);
                text.setStyle(FONT + (color == null ? "" : "-fx-background-color: " + color + ";"));
            }
        }
    }
}
