package org.phoebus.applications.git;

import java.util.ArrayList;
import java.util.List;

import org.phoebus.applications.git.GitRepository.ConfigEntry;
import org.phoebus.ui.dialog.DialogHelper;

import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.control.cell.TextFieldTableCell;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;

/** Dialog to edit the git configuration of the repository and of the user
 *
 *  <p>Result: Entries of the repository and of the user configuration
 */
@SuppressWarnings("nls")
class GitConfigDialog extends Dialog<List<List<ConfigEntry>>>
{
    /** Editable configuration entry */
    static class Row
    {
        final StringProperty key, value;

        Row(final String key, final String value)
        {
            this.key = new SimpleStringProperty(key);
            this.value = new SimpleStringProperty(value);
        }
    }

    /** One configuration file: Name, email and table of all entries */
    static class Scope
    {
        final TextField name = new TextField(), email = new TextField();
        final TableView<Row> table = new TableView<>();

        Scope(final List<ConfigEntry> entries)
        {
            for (ConfigEntry entry : entries)
                table.getItems().add(new Row(entry.key(), entry.value()));
            name.setText(get("user.name"));
            email.setText(get("user.email"));
        }

        private String get(final String key)
        {
            return table.getItems().stream().filter(r -> key.equals(r.key.get())).map(r -> r.value.get()).findFirst().orElse("");
        }

        /** @param key Key
         *  @param value Value, empty to remove the key
         */
        private void set(final String key, final String value)
        {
            final List<Row> rows = table.getItems().filtered(r -> key.equals(r.key.get()));
            if (value.isEmpty())
                table.getItems().removeAll(List.copyOf(rows));
            else if (rows.isEmpty())
                table.getItems().add(new Row(key, value));
            else if (! rows.get(0).value.get().equals(value))
            {
                // Keep position of first entry, drop other values
                rows.get(0).value.set(value);
                table.getItems().removeAll(List.copyOf(rows.subList(1, rows.size())));
            }
        }

        /** @return Entries, with name and email from the text fields */
        List<ConfigEntry> getEntries()
        {
            set("user.name", name.getText().trim());
            set("user.email", email.getText().trim());
            final List<ConfigEntry> result = new ArrayList<>();
            for (Row row : table.getItems())
                if (! row.key.get().isBlank())
                    result.add(new ConfigEntry(row.key.get().trim(), row.value.get()));
            return result;
        }

        Node createPane(final String info)
        {
            final GridPane grid = new GridPane();
            grid.setHgap(5);
            grid.setVgap(5);
            name.setPrefColumnCount(30);
            grid.addRow(0, new Label("user.name"), name);
            grid.addRow(1, new Label("user.email"), email);
            grid.add(new Label(info), 0, 2, 2, 1);

            final TableColumn<Row, String> key = new TableColumn<>(Messages.ColKey);
            key.setCellValueFactory(cell -> cell.getValue().key);
            key.setCellFactory(TextFieldTableCell.forTableColumn());
            key.setPrefWidth(250);
            final TableColumn<Row, String> value = new TableColumn<>(Messages.ColValue);
            value.setCellValueFactory(cell -> cell.getValue().value);
            value.setCellFactory(TextFieldTableCell.forTableColumn());
            value.setPrefWidth(350);
            table.getColumns().setAll(List.of(key, value));
            table.setEditable(true);
            table.setPrefHeight(300);

            final Button add = new Button(Messages.ConfigAdd);
            add.setOnAction(event ->
            {
                table.getItems().add(new Row("section.name", ""));
                table.getSelectionModel().clearAndSelect(table.getItems().size() - 1);
                table.edit(table.getItems().size() - 1, key);
            });
            final Button remove = new Button(Messages.ConfigRemove);
            remove.setOnAction(event -> table.getItems().removeAll(List.copyOf(table.getSelectionModel().getSelectedItems())));
            remove.disableProperty().bind(table.getSelectionModel().selectedItemProperty().isNull());

            final BorderPane pane = new BorderPane(table);
            pane.setTop(grid);
            BorderPane.setMargin(grid, new Insets(5, 0, 5, 0));
            pane.setBottom(new HBox(5, add, remove));
            pane.setPadding(new Insets(5));
            return pane;
        }
    }

    final Scope repository, user;

    /** @param owner Node for positioning
     *  @param repository_entries Configuration of the repository
     *  @param user_entries Configuration of the user
     */
    GitConfigDialog(final Node owner, final List<ConfigEntry> repository_entries, final List<ConfigEntry> user_entries)
    {
        repository = new Scope(repository_entries);
        user = new Scope(user_entries);
        setTitle(Messages.GitConfig);
        setHeaderText(Messages.GitConfigInfo);
        setResizable(true);
        final TabPane tabs = new TabPane(new Tab(Messages.ConfigRepository, repository.createPane(Messages.ConfigRepositoryInfo)),
                                         new Tab(Messages.ConfigUser + "  (" + GitRepository.getUserConfigFile() + ")",
                                                 user.createPane(Messages.ConfigUserInfo)));
        tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        getDialogPane().setContent(tabs);
        getDialogPane().getButtonTypes().setAll(ButtonType.OK, ButtonType.CANCEL);
        setResultConverter(button -> button == ButtonType.OK ? List.of(repository.getEntries(), user.getEntries()) : null);
        DialogHelper.positionDialog(this, owner, -350, -250);
    }
}
