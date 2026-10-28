package org.phoebus.applications.git;

import javafx.scene.control.ListCell;

/** List cell that colors one line of a unified diff */
@SuppressWarnings("nls")
class DiffLineCell extends ListCell<String>
{
    private static final String FONT = "-fx-font-family: monospace;";

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
        if (line.startsWith("+++")  ||  line.startsWith("---")  ||  line.startsWith("diff ")  ||  line.startsWith("commit "))
            setStyle(FONT + "-fx-font-weight: bold;");
        else if (line.startsWith("@@"))
            setStyle(FONT + "-fx-text-fill: #0050a0; -fx-background-color: #eef4fb;");
        else if (line.startsWith("+"))
            setStyle(FONT + "-fx-background-color: #e6ffec;");
        else if (line.startsWith("-"))
            setStyle(FONT + "-fx-background-color: #ffebe9;");
        else
            setStyle(FONT);
    }
}
