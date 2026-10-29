package org.phoebus.applications.git;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import org.phoebus.applications.git.GitRepository.CommitInfo;
import org.phoebus.applications.git.GitRepository.RebaseAction;
import org.phoebus.applications.git.GitRepository.RebaseStep;
import org.phoebus.ui.dialog.DialogHelper;

import javafx.application.Platform;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.event.ActionEvent;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.Alert;
import javafx.scene.control.Alert.AlertType;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar.ButtonData;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextArea;
import javafx.scene.control.cell.ComboBoxTableCell;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.util.StringConverter;

/** Dialog to plan an interactive rebase: Action for each commit, order of commits */
@SuppressWarnings("nls")
class InteractiveRebaseDialog extends Dialog<List<RebaseStep>>
{
    /** Row of the plan */
    static class Row
    {
        final ObjectProperty<RebaseAction> action = new SimpleObjectProperty<>(RebaseAction.PICK);
        final CommitInfo commit;

        Row(final CommitInfo commit)
        {
            this.commit = commit;
        }
    }

    /** Button that starts the rebase */
    static final ButtonType START = new ButtonType(Messages.RebaseStart, ButtonData.OK_DONE);

    private final TableView<Row> table = new TableView<>();

    /** @param owner Node for positioning
     *  @param commits Commits to rebase, oldest first
     *  @param pushed Commits that have been pushed
     */
    InteractiveRebaseDialog(final Node owner, final List<CommitInfo> commits, final Set<String> pushed)
    {
        setTitle(Messages.InteractiveRebase);
        setHeaderText(Messages.InteractiveRebaseInfo);
        setResizable(true);

        final TableColumn<Row, RebaseAction> action = new TableColumn<>(Messages.ColAction);
        action.setCellValueFactory(cell -> cell.getValue().action);
        action.setCellFactory(ComboBoxTableCell.forTableColumn(new StringConverter<>()
        {
            @Override
            public String toString(final RebaseAction value)
            {
                return value == null ? "" : value.name().toLowerCase(Locale.ROOT);
            }

            @Override
            public RebaseAction fromString(final String text)
            {
                return RebaseAction.valueOf(text.toUpperCase(Locale.ROOT));
            }
        }, RebaseAction.values()));
        action.setPrefWidth(100);
        table.getColumns().add(action);
        table.getColumns().add(TableHelper.createColumn(Messages.ColId, 70, r -> r.commit.id().substring(0, 7)));
        table.getColumns().add(TableHelper.createColumn(Messages.ColMessage, 350, r -> r.commit.message()));
        table.getColumns().add(TableHelper.createColumn(Messages.ColAuthor, 120, r -> r.commit.author()));
        table.getColumns().add(TableHelper.createColumn(Messages.RebasePushed, 70, r -> pushed.contains(r.commit.id()) ? "✓" : ""));
        for (TableColumn<Row, ?> col : table.getColumns())
            col.setSortable(false);
        table.setEditable(true);
        for (CommitInfo commit : commits)
            table.getItems().add(new Row(commit));
        table.setPrefSize(750, 300);

        final Button up = new Button(Messages.MoveUp);
        up.setOnAction(event -> move(-1));
        final Button down = new Button(Messages.MoveDown);
        down.setOnAction(event -> move(+1));
        final VBox buttons = new VBox(5, up, down);
        buttons.setPadding(new Insets(0, 0, 0, 5));

        final Label legend = new Label(Messages.RebaseLegend);
        legend.setWrapText(true);
        final VBox bottom = new VBox(5, legend);
        if (! pushed.isEmpty())
        {
            final Label warning = new Label(Messages.RebasePushedWarning);
            warning.setWrapText(true);
            warning.setStyle("-fx-text-fill: #c00000;");
            bottom.getChildren().add(warning);
        }
        bottom.setPadding(new Insets(5, 0, 0, 0));
        final BorderPane content = new BorderPane(table);
        content.setRight(new HBox(buttons));
        content.setBottom(bottom);
        getDialogPane().setContent(content);
        getDialogPane().getButtonTypes().setAll(START, ButtonType.CANCEL);

        // First step cannot be combined with a previous commit
        getDialogPane().lookupButton(START).addEventFilter(ActionEvent.ACTION, event ->
        {
            final String problem = validate();
            if (problem != null)
            {
                final Alert alert = new Alert(AlertType.WARNING, problem, ButtonType.OK);
                alert.setHeaderText(null);
                alert.initOwner(getDialogPane().getScene().getWindow());
                alert.showAndWait();
                event.consume();
            }
        });
        setResultConverter(button -> button == START ? getSteps() : null);
        DialogHelper.positionDialog(this, owner, -400, -250);
    }

    /** @return Problem with the plan, <code>null</code> if OK */
    private String validate()
    {
        for (Row row : table.getItems())
        {
            final RebaseAction action = row.action.get();
            if (action == RebaseAction.DROP)
                continue;
            if (action == RebaseAction.SQUASH  ||  action == RebaseAction.FIXUP)
                return Messages.RebaseFirstSquash;
            return null;
        }
        return null;
    }

    /** @param direction Move selected row up (-1) or down */
    private void move(final int direction)
    {
        final int index = table.getSelectionModel().getSelectedIndex();
        final int other = index + direction;
        if (index < 0  ||  other < 0  ||  other >= table.getItems().size())
            return;
        final Row row = table.getItems().remove(index);
        table.getItems().add(other, row);
        table.getSelectionModel().clearAndSelect(other);
    }

    /** @return Rows of the plan, for tests */
    List<Row> getRows()
    {
        return table.getItems();
    }

    /** @return Steps of the plan */
    List<RebaseStep> getSteps()
    {
        return table.getItems().stream().map(row -> new RebaseStep(row.action.get(), row.commit)).toList();
    }

    /** Prompt for a commit message, waiting for the user
     *
     *  <p>Called on a background thread by the rebase
     *
     *  @param owner Node for positioning
     *  @param message Message to edit
     *  @return Edited message, or original message if cancelled
     */
    static String editMessage(final Node owner, final String message)
    {
        final CompletableFuture<String> result = new CompletableFuture<>();
        final Runnable prompt = () ->
        {
            final TextArea text = new TextArea(message);
            text.setPrefSize(600, 300);
            final Dialog<String> dialog = new Dialog<>();
            dialog.setTitle(Messages.InteractiveRebase);
            dialog.setHeaderText(Messages.RebaseEditMessage);
            dialog.setResizable(true);
            dialog.getDialogPane().setContent(text);
            dialog.getDialogPane().getButtonTypes().setAll(ButtonType.OK, ButtonType.CANCEL);
            dialog.setResultConverter(button -> button == ButtonType.OK ? text.getText() : null);
            DialogHelper.positionDialog(dialog, owner, -300, -200);
            result.complete(dialog.showAndWait().orElse(message));
        };
        if (Platform.isFxApplicationThread())
            prompt.run();
        else
            Platform.runLater(prompt);
        try
        {
            return result.get();
        }
        catch (Exception ex)
        {
            return message;
        }
    }
}
