package org.phoebus.applications.git;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;

import org.phoebus.applications.git.MergeModel.Chunk;
import org.phoebus.applications.git.MergeModel.Choice;
import org.phoebus.framework.jobs.JobManager;
import org.phoebus.ui.dialog.DialogHelper;
import org.phoebus.ui.dialog.ExceptionDetailsErrorDialog;
import org.phoebus.ui.docking.DockItem;
import org.phoebus.ui.docking.DockPane;

import javafx.application.Platform;
import javafx.beans.property.SimpleStringProperty;
import javafx.geometry.Insets;
import javafx.geometry.Orientation;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Alert;
import javafx.scene.control.Alert.AlertType;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.SplitPane;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.cell.ComboBoxTableCell;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextInputDialog;
import javafx.scene.control.TitledPane;
import javafx.scene.control.ToolBar;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;

/** Dock tab to resolve the conflicts of a file: Base, ours, theirs, and the result
 *
 *  <p>Display files are also offered a merge by widget.
 */
@SuppressWarnings("nls")
class MergeView
{
    /** Line of base, ours or theirs: Text and its state */
    private record SideLine(String text, int conflict) {}

    /** Line of the result
     *  @param text Text
     *  @param conflict Index of the conflict, -1 if the line merged without conflict
     *  @param choice How that conflict is resolved
     *  @param marker Is it a conflict marker line?
     */
    private record ResultLine(String text, int conflict, Choice choice, boolean marker) {}

    private final GitActions actions;
    private final String path;
    private final DockItem tab;
    private final BorderPane layout = new BorderPane();
    private final ListView<SideLine> base = new ListView<>();
    private final ListView<SideLine> ours = new ListView<>();
    private final ListView<SideLine> theirs = new ListView<>();
    private final ListView<ResultLine> result = new ListView<>();
    private final Label status = new Label();
    private final List<Button> choice_buttons = new ArrayList<>();
    private final Button previous = new Button(Messages.PreviousConflict);
    private final Button next = new Button(Messages.NextConflict);
    private final Button save = new Button(Messages.SaveAndResolve);

    private MergeModel model;

    /** Index of the current conflict */
    private int current = 0;

    /** @param actions Actions, also provides the repository
     *  @param path File with conflict
     */
    MergeView(final GitActions actions, final String path)
    {
        this.actions = actions;
        this.path = path;

        tab = new DockItem(Messages.MergeTool + ": " + new File(path).getName());
        tab.setContent(layout);
        layout.setCenter(new Label(Messages.Loading));
        DockPane.getActiveDockPane().addTab(tab);
        actions.addTab(tab);
        tab.select();
        load();
    }

    private void load()
    {
        final GitRepository git = actions.getRepository();
        if (git == null)
            return;
        JobManager.schedule(Messages.MergeTool, monitor ->
        {
            try
            {
                if (! git.exists(new Revision.Stage(2), path)  ||  ! git.exists(new Revision.Stage(3), path))
                    throw new Exception(Messages.MergeDeleted);
                final byte[] b = git.getContent(new Revision.Stage(1), path);
                final byte[] o = git.getContent(new Revision.Stage(2), path);
                final byte[] t = git.getContent(new Revision.Stage(3), path);
                if (MergeModel.isBinary(b)  ||  MergeModel.isBinary(o)  ||  MergeModel.isBinary(t))
                    throw new Exception(Messages.MergeBinary);
                final MergeModel merged = new MergeModel(b, o, t);
                // Display files can also be merged by widget
                BobMerge by_widget = null;
                if (BobDiff.supports(path))
                    try
                    {
                        by_widget = new BobMerge(b, o, t);
                    }
                    catch (Exception ex)
                    {
                        GitApp.logger.log(Level.FINE, "Cannot merge " + path + " by widget", ex);
                    }
                final BobMerge widget_merge = by_widget;
                Platform.runLater(() -> show(merged, widget_merge));
            }
            catch (Exception ex)
            {
                tab.prepareToClose();
                Platform.runLater(() ->
                {
                    tab.close();
                    final Alert alert = new Alert(AlertType.INFORMATION, path + "\n\n" + ex.getMessage(), ButtonType.OK);
                    alert.setTitle(Messages.MergeTool);
                    alert.setHeaderText(null);
                    DialogHelper.positionDialog(alert, DockPane.getActiveDockPane(), -200, -100);
                    alert.show();
                });
            }
        });
    }

    /** @param merged Merge to show
     *  @param widget_merge Merge by widget, <code>null</code> if not possible
     */
    private void show(final MergeModel merged, final BobMerge widget_merge)
    {
        model = merged;
        for (ListView<SideLine> side : List.of(base, ours, theirs))
        {
            side.setCellFactory(view -> new SideCell());
            side.setFixedCellSize(22);
        }
        result.setCellFactory(view -> new ResultCell());
        result.setFixedCellSize(22);
        // Clicking a conflict in the result selects it
        result.setOnMouseClicked(event ->
        {
            final ResultLine line = result.getSelectionModel().getSelectedItem();
            if (line != null  &&  line.conflict >= 0)
            {
                current = line.conflict;
                update(false);
                if (event.getButton() == MouseButton.PRIMARY  &&  event.getClickCount() == 2)
                    edit();
            }
        });

        base.getItems().setAll(toSide(model.getBase(), 0));
        ours.getItems().setAll(toSide(model.getOurs(), 1));
        theirs.getItems().setAll(toSide(model.getTheirs(), 2));

        final SplitPane sides = new SplitPane(titled(Messages.MergeOurs, ours),
                                              titled(Messages.MergeBase, base),
                                              titled(Messages.MergeTheirs, theirs));
        final SplitPane split = new SplitPane(sides, titled(Messages.MergeResult, result));
        split.setOrientation(Orientation.VERTICAL);
        split.setDividerPositions(0.45);

        final BorderPane text_pane = new BorderPane(split);
        text_pane.setTop(createToolbar());
        if (widget_merge == null)
            layout.setCenter(text_pane);
        else
        {
            final Tab by_widget = new Tab(Messages.MergeByWidget, createWidgetPane(widget_merge));
            final Tab by_text = new Tab(Messages.MergeByText, text_pane);
            final TabPane tabs = new TabPane(by_widget, by_text);
            tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
            layout.setCenter(tabs);
        }
        update(true);
    }

    /** @param merge Merge by widget
     *  @return Pane with changes taken from 'theirs' and conflicts by widget and property
     */
    private Node createWidgetPane(final BobMerge merge)
    {
        final ListView<String> automatic = new ListView<>();
        automatic.getItems().setAll(merge.getAutomatic());
        automatic.setPlaceholder(new Label(Messages.MergeNoAutomatic));

        final TableView<BobMerge.Conflict> conflicts = new TableView<>();
        conflicts.getColumns().add(TableHelper.createColumn(Messages.ColWidget, 250, c -> c.widget));
        conflicts.getColumns().add(TableHelper.createColumn(Messages.ColProperty, 120,
                                   c -> c.property == null ? Messages.MergeWholeWidget : c.property));
        conflicts.getColumns().add(TableHelper.createColumn(Messages.MergeOurs, 200, c -> c.ours));
        conflicts.getColumns().add(TableHelper.createColumn(Messages.MergeTheirs, 200, c -> c.theirs));
        final TableColumn<BobMerge.Conflict, String> choice = new TableColumn<>(Messages.ColChoice);
        choice.setPrefWidth(150);
        final String[] labels = { Messages.Unresolved, Messages.UseOurs, Messages.UseTheirs };
        choice.setCellValueFactory(cell -> new SimpleStringProperty(labels[cell.getValue().choice.ordinal()]));
        choice.setCellFactory(ComboBoxTableCell.forTableColumn(labels));
        choice.setOnEditCommit(event ->
        {
            event.getRowValue().choice = BobMerge.Choice.values()[List.of(labels).indexOf(event.getNewValue())];
            updateWidgetStatus(merge);
        });
        conflicts.getColumns().add(choice);
        conflicts.setEditable(true);
        conflicts.getItems().setAll(merge.getConflicts());
        conflicts.setPlaceholder(new Label(Messages.NoConflicts));

        final Button all_ours = new Button(Messages.MergeAllOurs);
        all_ours.setOnAction(event ->
        {
            merge.getConflicts().forEach(c -> c.choice = BobMerge.Choice.OURS);
            conflicts.refresh();
            updateWidgetStatus(merge);
        });
        final Button all_theirs = new Button(Messages.MergeAllTheirs);
        all_theirs.setOnAction(event ->
        {
            merge.getConflicts().forEach(c -> c.choice = BobMerge.Choice.THEIRS);
            conflicts.refresh();
            updateWidgetStatus(merge);
        });
        final Button save_widgets = new Button(Messages.SaveAndResolve);
        save_widgets.setOnAction(event -> saveWidgets(merge));
        final Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        final ToolBar toolbar = new ToolBar(widget_status, all_ours, all_theirs, spacer, save_widgets);
        updateWidgetStatus(merge);

        final SplitPane split = new SplitPane(titled(Messages.MergeAutomatic + " (" + merge.getAutomatic().size() + ")", automatic),
                                              titled(Messages.MergeConflicts + " (" + merge.getConflicts().size() + ")", conflicts));
        split.setOrientation(Orientation.VERTICAL);
        split.setDividerPositions(0.35);
        final BorderPane pane = new BorderPane(split);
        pane.setTop(toolbar);
        return pane;
    }

    private final Label widget_status = new Label();

    private void updateWidgetStatus(final BobMerge merge)
    {
        widget_status.setText(Messages.WidgetConflictStatus.formatted(merge.getConflicts().size(), merge.getUnresolved()));
    }

    /** Write the result of the merge by widget and stage it */
    private void saveWidgets(final BobMerge merge)
    {
        final int unresolved = merge.getUnresolved();
        if (unresolved > 0)
        {
            final Alert alert = new Alert(AlertType.INFORMATION, Messages.WidgetUnresolved.formatted(unresolved), ButtonType.OK);
            alert.setTitle(Messages.MergeTool);
            alert.setHeaderText(null);
            DialogHelper.positionDialog(alert, layout, -200, -100);
            alert.showAndWait();
            return;
        }
        final byte[] content;
        try
        {
            content = merge.getResult();
        }
        catch (Exception ex)
        {
            ExceptionDetailsErrorDialog.openError(layout, Messages.MergeTool, Messages.BobMergeUnsupported, ex);
            return;
        }
        actions.run(Messages.MergeTool, git ->
        {
            Files.write(new File(git.getWorkTree(), path).toPath(), content);
            git.stage(List.of(path));
            tab.prepareToClose();
            Platform.runLater(tab::close);
        });
    }

    private static Node titled(final String title, final Node content)
    {
        final TitledPane pane = new TitledPane(title, content);
        pane.setCollapsible(false);
        pane.setMaxHeight(Double.MAX_VALUE);
        return pane;
    }

    private Node createToolbar()
    {
        previous.setOnAction(event -> select(current - 1));
        next.setOnAction(event -> select(current + 1));
        addChoice(Messages.UseOurs, Choice.OURS);
        addChoice(Messages.UseTheirs, Choice.THEIRS);
        addChoice(Messages.UseOursThenTheirs, Choice.OURS_THEN_THEIRS);
        addChoice(Messages.UseTheirsThenOurs, Choice.THEIRS_THEN_OURS);
        final Button edit = new Button(Messages.EditConflict);
        edit.setOnAction(event -> edit());
        choice_buttons.add(edit);
        save.setOnAction(event -> save());
        final Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        final ToolBar toolbar = new ToolBar(previous, next, status);
        toolbar.getItems().addAll(choice_buttons);
        toolbar.getItems().addAll(spacer, save);
        return toolbar;
    }

    private void addChoice(final String label, final Choice choice)
    {
        final Button button = new Button(label);
        button.setOnAction(event -> choose(choice));
        choice_buttons.add(button);
    }

    /** @param lines Lines of one side
     *  @param side 0 base, 1 ours, 2 theirs
     *  @return Lines with the index of the conflict that they belong to
     */
    private List<SideLine> toSide(final List<byte[]> lines, final int side)
    {
        final int[] conflict = new int[lines.size()];
        java.util.Arrays.fill(conflict, -1);
        final List<Chunk> conflicts = model.getConflicts();
        for (int c=0; c<conflicts.size(); ++c)
        {
            final Chunk chunk = conflicts.get(c);
            final int start = side == 0 ? chunk.base_start : side == 1 ? chunk.ours_start : chunk.theirs_start;
            final int count = side == 0 ? chunk.base.size() : side == 1 ? chunk.ours.size() : chunk.theirs.size();
            for (int i=Math.max(0, start); start >= 0  &&  i<start+count  &&  i<conflict.length; ++i)
                conflict[i] = c;
        }
        final List<SideLine> result = new ArrayList<>(lines.size());
        for (int i=0; i<lines.size(); ++i)
            result.add(new SideLine(MergeModel.toText(lines.get(i)), conflict[i]));
        return result;
    }

    /** @param index Conflict to select */
    private void select(final int index)
    {
        if (index < 0  ||  index >= model.getConflicts().size())
            return;
        current = index;
        update(true);
    }

    /** Resolve the current conflict
     *  @param choice How
     */
    void choose(final Choice choice)
    {
        if (model.getConflicts().isEmpty())
            return;
        model.getConflicts().get(current).choice = choice;
        // Continue with the next unresolved conflict
        final int unresolved = findUnresolved();
        if (unresolved >= 0)
            current = unresolved;
        update(true);
    }

    /** @return Index of next unresolved conflict after the current one, wrapping, or -1 */
    private int findUnresolved()
    {
        final List<Chunk> conflicts = model.getConflicts();
        for (int i=1; i<=conflicts.size(); ++i)
        {
            final int index = (current + i) % conflicts.size();
            if (! conflicts.get(index).isResolved())
                return index;
        }
        return -1;
    }

    /** Edit the result of the current conflict */
    private void edit()
    {
        if (model.getConflicts().isEmpty())
            return;
        final Chunk chunk = model.getConflicts().get(current);
        final List<byte[]> lines = chunk.isResolved() ? chunk.getResult() : model.getResultLines(chunk);
        final StringBuilder text = new StringBuilder();
        for (byte[] line : lines)
            text.append(MergeModel.toText(line)).append('\n');
        final TextInputDialog dialog = new TextInputDialog();
        final TextArea area = new TextArea(text.toString());
        area.setStyle("-fx-font-family: monospace;");
        area.setPrefSize(800, 400);
        dialog.getDialogPane().setContent(area);
        dialog.setTitle(Messages.EditConflict);
        dialog.setHeaderText(Messages.EditConflictInfo);
        dialog.setResizable(true);
        dialog.setResultConverter(button -> button == ButtonType.OK ? area.getText() : null);
        DialogHelper.positionDialog(dialog, layout, -400, -250);
        dialog.showAndWait().ifPresent(edited ->
        {
            model.setEdited(chunk, edited);
            update(true);
        });
    }

    /** Show the current state
     *  @param scroll Scroll to the current conflict?
     */
    private void update(final boolean scroll)
    {
        final int total = model.getConflicts().size();
        status.setText(total == 0
                       ? Messages.NoConflicts
                       : Messages.ConflictStatus.formatted(current + 1, total, model.getUnresolved()));
        previous.setDisable(current <= 0);
        next.setDisable(current >= total - 1);
        choice_buttons.forEach(button -> button.setDisable(total == 0));

        final List<ResultLine> lines = new ArrayList<>();
        int conflict = -1, first_line = 0;
        for (Chunk chunk : model.getChunks())
        {
            if (chunk.conflict)
                ++conflict;
            final int index = chunk.conflict ? conflict : -1;
            if (index == current)
                first_line = lines.size();
            for (byte[] line : model.getResultLines(chunk))
            {
                final String text = MergeModel.toText(line);
                final boolean marker = ! chunk.isResolved()  &&
                    (text.equals(MergeModel.MARK_OURS)  ||  text.equals(MergeModel.MARK_BASE)  ||
                     text.equals(MergeModel.MARK_SEP)   ||  text.equals(MergeModel.MARK_THEIRS));
                lines.add(new ResultLine(text, index, chunk.choice, marker));
            }
        }
        result.getItems().setAll(lines);
        for (ListView<SideLine> side : List.of(base, ours, theirs))
            side.refresh();
        if (scroll  &&  total > 0)
        {
            result.scrollTo(Math.max(0, first_line - 3));
            final Chunk chunk = model.getConflicts().get(current);
            base.scrollTo(Math.max(0, chunk.base_start - 3));
            ours.scrollTo(Math.max(0, chunk.ours_start - 3));
            theirs.scrollTo(Math.max(0, chunk.theirs_start - 3));
        }
    }

    /** Write the result. Without unresolved conflicts, stage it */
    private void save()
    {
        final int unresolved = model.getUnresolved();
        if (unresolved > 0)
        {
            final Alert alert = new Alert(AlertType.CONFIRMATION, Messages.SaveUnresolved.formatted(unresolved),
                                          ButtonType.OK, ButtonType.CANCEL);
            alert.setTitle(Messages.MergeTool);
            alert.setHeaderText(null);
            DialogHelper.positionDialog(alert, layout, -200, -100);
            if (alert.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK)
                return;
        }
        final byte[] content = model.getResult();
        actions.run(Messages.MergeTool, git ->
        {
            Files.write(new File(git.getWorkTree(), path).toPath(), content);
            if (unresolved == 0)
            {
                git.stage(List.of(path));
                tab.prepareToClose();
                Platform.runLater(tab::close);
            }
        });
    }

    private static final String CURRENT = "-fx-font-weight: bold; ";

    /** Background for a resolved conflict
     *  @param choice How the conflict was resolved
     */
    private static String getChoiceColor(final Choice choice)
    {
        return switch (choice)
        {
            case UNRESOLVED -> "#ffd7d7";
            case OURS -> "#e6ffed";
            case THEIRS -> "#e0ecff";
            case EDITED -> "#fff6d5";
            default -> "#efe6ff";
        };
    }

    private class SideCell extends ListCell<SideLine>
    {
        @Override
        protected void updateItem(final SideLine line, final boolean empty)
        {
            super.updateItem(line, empty);
            if (empty  ||  line == null)
            {
                setText(null);
                setStyle(null);
                return;
            }
            setText(line.text);
            String style = "-fx-font-family: monospace;";
            if (line.conflict >= 0)
            {
                style += "-fx-background-color: " + getChoiceColor(model.getConflicts().get(line.conflict).choice) + ";";
                if (line.conflict == current)
                    style += CURRENT;
            }
            setStyle(style);
        }
    }

    private class ResultCell extends ListCell<ResultLine>
    {
        @Override
        protected void updateItem(final ResultLine line, final boolean empty)
        {
            super.updateItem(line, empty);
            if (empty  ||  line == null)
            {
                setText(null);
                setStyle(null);
                return;
            }
            setText(line.text);
            String style = "-fx-font-family: monospace;";
            if (line.conflict >= 0)
            {
                style += "-fx-background-color: " + getChoiceColor(line.choice) + ";";
                if (line.marker)
                    style += "-fx-text-fill: #a00000;";
                if (line.conflict == current)
                    style += CURRENT;
            }
            setStyle(style);
            setAlignment(Pos.CENTER_LEFT);
            setPadding(new Insets(0, 4, 0, 4));
        }
    }
}
