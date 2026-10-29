package org.phoebus.applications.git;

import java.io.File;
import java.util.Collection;

import org.phoebus.ui.docking.DockItem;
import org.phoebus.ui.docking.DockPane;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;

/** Dock tab that compares two revisions: Changed files and their diff */
@SuppressWarnings("nls")
class CompareView
{
    private final CommitFilesPane files;
    private final DockItem tab;
    private Revision from, to;
    private final Collection<String> paths;
    private final Label title = new Label();

    /** @param actions Actions, also provides the repository
     *  @param a Old side
     *  @param b New side
     *  @param paths Files to compare, <code>null</code> for all
     */
    CompareView(final GitActions actions, final Revision a, final Revision b, final Collection<String> paths)
    {
        this.from = a;
        this.to = b;
        this.paths = paths;
        files = new CommitFilesPane(actions::getRepository, ex -> actions.showError(Messages.Compare, ex),
                                    path -> new FileHistoryView(actions::getRepository, path,
                                                                ex -> actions.showError(Messages.FileHistory, ex),
                                                                null, actions),
                                    (path, editor) ->
                                    {
                                        final GitRepository git = actions.getRepository();
                                        final File file = git == null ? null : new File(git.getWorkTree(), path);
                                        if (file != null  &&  file.isFile())
                                            OpenFiles.open(file, editor);
                                    },
                                    actions);

        title.setStyle("-fx-font-weight: bold;");
        final Button swap = new Button(Messages.Swap);
        swap.setOnAction(event ->
        {
            final Revision old = from;
            from = to;
            to = old;
            load();
        });
        final Button refresh = new Button(Messages.Refresh);
        refresh.setOnAction(event -> load());
        final Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        final HBox top = new HBox(5, title, spacer, swap, refresh);
        top.setAlignment(Pos.CENTER_LEFT);
        top.setPadding(new Insets(5));

        final BorderPane layout = new BorderPane(files);
        layout.setTop(top);

        final String name = paths != null  &&  paths.size() == 1
                          ? new File(paths.iterator().next()).getName() + ": "
                          : "";
        tab = new DockItem(Messages.Compare + " " + name + a + " ↔ " + b);
        tab.setContent(layout);
        DockPane.getActiveDockPane().addTab(tab);
        tab.select();
        load();
    }

    private void load()
    {
        String text = from + "  ↔  " + to;
        if (paths != null)
            text += "   (" + String.join(", ", paths) + ")";
        title.setText(text);
        files.showCompare(from, to, paths);
    }
}
