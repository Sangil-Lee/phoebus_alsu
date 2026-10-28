package org.phoebus.applications.git;

import java.util.List;
import java.util.function.Supplier;

import javafx.scene.control.Button;
import javafx.scene.control.ContentDisplay;
import javafx.scene.control.ListCell;
import javafx.scene.layout.HBox;

/** List cell that colors one line of a unified diff
 *
 *  <p>Hunk header lines get buttons when the diff offers hunk actions.
 */
@SuppressWarnings("nls")
class DiffLineCell extends ListCell<String>
{
    private static final String FONT = "-fx-font-family: monospace;";

    /** Provides the actions for hunks, <code>null</code> if none */
    private final Supplier<DiffPane.HunkActions> actions;

    /** @param actions Provides the actions for hunks, may provide <code>null</code> */
    DiffLineCell(final Supplier<DiffPane.HunkActions> actions)
    {
        this.actions = actions;
        setContentDisplay(ContentDisplay.LEFT);
    }

    @Override
    protected void updateItem(final String line, final boolean empty)
    {
        super.updateItem(line, empty);
        setGraphic(null);
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
        {
            setStyle(FONT + "-fx-text-fill: #0050a0; -fx-background-color: #eef4fb;");
            final DiffPane.HunkActions hunk_actions = actions.get();
            if (hunk_actions != null)
                setGraphic(createButtons(hunk_actions, getHunkIndex()));
        }
        else if (line.startsWith("+"))
            setStyle(FONT + "-fx-background-color: #e6ffec;");
        else if (line.startsWith("-"))
            setStyle(FONT + "-fx-background-color: #ffebe9;");
        else
            setStyle(FONT);
    }

    /** @return Index of this hunk: Number of hunk headers before this line */
    private int getHunkIndex()
    {
        final List<String> lines = getListView().getItems();
        int hunk = 0;
        for (int i=0; i<getIndex()  &&  i<lines.size(); ++i)
            if (lines.get(i).startsWith("@@"))
                ++hunk;
        return hunk;
    }

    private static HBox createButtons(final DiffPane.HunkActions actions, final int hunk)
    {
        final HBox buttons = new HBox(3);
        final List<String> labels = actions.getLabels();
        for (int i=0; i<labels.size(); ++i)
        {
            final int action = i;
            final Button button = new Button(labels.get(i));
            button.setStyle("-fx-font-size: 90%; -fx-padding: 0 6 0 6;");
            button.setOnAction(event -> actions.run(action, hunk));
            buttons.getChildren().add(button);
        }
        return buttons;
    }
}
