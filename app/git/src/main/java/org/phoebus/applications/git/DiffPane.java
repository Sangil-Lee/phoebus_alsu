package org.phoebus.applications.git;

import java.util.Arrays;
import java.util.List;
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
import javafx.scene.control.ListView;
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
    }

    /** Side-by-side mode, shared by all diff panes */
    private static final BooleanProperty side_by_side = new SimpleBooleanProperty(false);

    /** Ignore white space, shared by all diff panes */
    private static final BooleanProperty ignore_whitespace = new SimpleBooleanProperty(false);

    private final Supplier<GitRepository> repo;
    private final Consumer<Exception> on_error;
    private final ListView<String> unified = new ListView<>();
    private final ListView<Row> split = new ListView<>();

    /** Lines of the diff that's currently shown */
    private List<String> lines = List.of();

    /** Updates this pane when the mode changes. Registered as weak listener,
     *  so the shared mode property does not keep panes of closed tabs
     */
    private final ChangeListener<Boolean> mode_listener = (p, o, n) -> showLines();

    /** Most recent query and hunk actions, to re-compute the diff */
    private DiffQuery last_query = null;
    private HunkActions last_actions = null;

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
        split.setCellFactory(view -> new SideBySideCell());

        final CheckBox toggle = new CheckBox(Messages.SideBySide);
        toggle.selectedProperty().bindBidirectional(side_by_side);
        final CheckBox whitespace = new CheckBox(Messages.IgnoreWhitespace);
        whitespace.selectedProperty().bindBidirectional(ignore_whitespace);
        final HBox options = new HBox(10, whitespace, toggle);
        options.setAlignment(Pos.CENTER_RIGHT);
        options.setPadding(new Insets(2, 5, 2, 5));
        setTop(options);

        side_by_side.addListener(new WeakChangeListener<>(mode_listener));
        ignore_whitespace.addListener(new WeakChangeListener<>(whitespace_listener));
        showLines();
    }

    /** Compute the most recent diff again */
    private void recompute()
    {
        if (last_query != null)
            show(last_query, last_actions);
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
        last_query = query;
        last_actions = actions;
        final GitRepository current = repo.get();
        if (current == null)
            return;
        final boolean ignore = ignore_whitespace.get();
        final long this_request = request.incrementAndGet();
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

    /** Clear the diff, dropping pending results */
    void clear()
    {
        last_query = null;
        last_actions = null;
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
