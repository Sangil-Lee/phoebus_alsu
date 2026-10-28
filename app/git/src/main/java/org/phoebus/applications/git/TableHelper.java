package org.phoebus.applications.git;

import java.util.function.Function;

import javafx.beans.property.SimpleStringProperty;
import javafx.scene.control.TableColumn;

/** Table helpers */
class TableHelper
{
    /** @param title Column title
     *  @param width Preferred width
     *  @param value Text to show for a row
     *  @return Read-only text column
     */
    static <T> TableColumn<T, String> createColumn(final String title, final double width,
                                                   final Function<T, String> value)
    {
        final TableColumn<T, String> col = new TableColumn<>(title);
        col.setPrefWidth(width);
        col.setCellValueFactory(cell -> new SimpleStringProperty(value.apply(cell.getValue())));
        return col;
    }
}
