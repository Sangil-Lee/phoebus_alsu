package org.phoebus.applications.git;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;

import org.csstudio.display.builder.editor.app.DisplayEditorInstance;
import org.csstudio.display.builder.model.DisplayModel;
import org.csstudio.display.builder.model.Widget;
import org.phoebus.applications.git.BobDiff.Change;
import org.phoebus.applications.git.BobDiff.Kind;
import org.phoebus.framework.jobs.JobManager;
import org.phoebus.ui.dialog.DialogHelper;
import org.phoebus.ui.dialog.ExceptionDetailsErrorDialog;
import org.phoebus.ui.docking.DockItem;
import org.phoebus.ui.docking.DockPane;
import org.phoebus.ui.docking.DockStage;

import javafx.application.Platform;
import javafx.scene.control.Alert;
import javafx.scene.control.Alert.AlertType;
import javafx.scene.control.ButtonType;
import javafx.stage.Stage;

/** Git operations for displays in the display editor */
@SuppressWarnings("nls")
class GitEditorSupport
{
    /** Display file in a repository
     *  @param file Display file
     *  @param git Repository, to be closed by the caller
     *  @param path Path of the file in the repository
     */
    record DisplayFile(File file, GitRepository git, String path) {}

    /** @param model Display model
     *  @return Editor that shows the model, or <code>null</code>
     */
    static DisplayEditorInstance findEditor(final DisplayModel model)
    {
        for (Stage stage : DockStage.getDockStages())
            for (DockPane pane : DockStage.getDockPanes(stage))
                for (DockItem item : pane.getDockItems())
                    if ((Object) item.getApplication() instanceof DisplayEditorInstance editor  &&
                        editor.getDisplayEditor().getModel() == model)
                        return editor;
        return null;
    }

    /** @param file Display file
     *  @return Editor that has the file open, or <code>null</code>
     */
    static DisplayEditorInstance findEditor(final File file)
    {
        for (Stage stage : DockStage.getDockStages())
            for (DockPane pane : DockStage.getDockPanes(stage))
                for (DockItem item : pane.getDockItems())
                    if ((Object) item.getApplication() instanceof DisplayEditorInstance editor)
                    {
                        final DisplayModel model = editor.getDisplayEditor().getModel();
                        final File shown = model == null ? null : WidgetKeys.getFile(model);
                        if (shown != null  &&  shown.getAbsoluteFile().equals(file.getAbsoluteFile()))
                            return editor;
                    }
        return null;
    }

    /** @param model Display model
     *  @return Display file and its repository
     *  @throws Exception if the display is not a file in a repository
     */
    static DisplayFile open(final DisplayModel model) throws Exception
    {
        final File file = WidgetKeys.getFile(model);
        if (file == null)
            throw new Exception(Messages.NoDisplayFile);
        final GitRepository git;
        try
        {
            git = new GitRepository(file.getAbsoluteFile().getParentFile());
        }
        catch (Exception ex)
        {
            throw new Exception(Messages.NotInRepository + "\n" + file, ex);
        }
        final String path = git.getWorkTree().getAbsoluteFile().toPath()
                               .relativize(file.getAbsoluteFile().toPath())
                               .toString().replace(File.separatorChar, '/');
        return new DisplayFile(file, git, path);
    }

    /** @param title Title of the operation
     *  @param ex Error
     */
    private static void showError(final String title, final Exception ex)
    {
        GitApp.logger.log(Level.FINE, title, ex);
        ExceptionDetailsErrorDialog.openError(title, ex.getMessage(), ex);
    }

    /** Select the widgets that differ from HEAD, including unsaved changes
     *  @param model Display model in an editor
     */
    static void selectChangedWidgets(final DisplayModel model)
    {
        final DisplayEditorInstance editor = findEditor(model);
        if (editor == null)
            return;
        final byte[] current;
        try
        {
            current = WidgetKeys.write(model);
        }
        catch (Exception ex)
        {
            showError(Messages.GitSelectChanged, ex);
            return;
        }
        JobManager.schedule(Messages.GitSelectChanged, monitor ->
        {
            try
            {
                final DisplayFile display = open(model);
                final List<Change> changes;
                try
                {
                    final GitRepository git = display.git();
                    final byte[] head = git.exists(git.resolve("HEAD"), display.path())
                                      ? WidgetKeys.normalize(git.getContent(git.resolve("HEAD"), display.path()))
                                      : new byte[0];
                    changes = BobDiff.compare(head, current);
                }
                finally
                {
                    display.git().close();
                }
                Platform.runLater(() -> select(editor, model, changes));
            }
            catch (Exception ex)
            {
                showError(Messages.GitSelectChanged, ex);
            }
        });
    }

    /** @param editor Editor
     *  @param model Its model
     *  @param changes Changes of the model compared to HEAD
     */
    private static void select(final DisplayEditorInstance editor, final DisplayModel model, final List<Change> changes)
    {
        final Map<String, Widget> widgets = WidgetKeys.of(model);
        final List<Widget> selection = new ArrayList<>();
        int added = 0, changed = 0, removed = 0;
        for (Change change : changes)
        {
            if (change.kind() == Kind.REMOVED)
            {
                ++removed;
                continue;
            }
            final Widget widget = widgets.get(change.key());
            if (widget == null  ||  selection.contains(widget))
                continue;
            selection.add(widget);
            if (change.kind() == Kind.ADDED)
                ++added;
            else
                ++changed;
        }
        final boolean display_changed = changes.stream().anyMatch(change -> change.key().isEmpty());
        editor.getDisplayEditor().getWidgetSelectionHandler().setSelection(selection);

        final String text = changes.isEmpty()
                          ? Messages.NoChangedWidgets
                          : Messages.ChangedWidgetsInfo.formatted(added, changed, removed) +
                            (display_changed ? "\n" + Messages.DisplayPropertiesChanged : "");
        final Alert info = new Alert(AlertType.INFORMATION, text, ButtonType.OK);
        info.setTitle(Messages.GitSelectChanged);
        info.setHeaderText(null);
        DialogHelper.positionDialog(info, editor.getDisplayEditor().getContextMenuNode(), -200, -100);
        info.show();
    }

    /** Show the history of a widget
     *  @param widget Widget in an editor
     */
    static void showWidgetHistory(final Widget widget)
    {
        try
        {
            final DisplayModel model = widget.getDisplayModel();
            final String key = WidgetKeys.keyOf(widget);
            if (key == null)
                throw new Exception("Cannot locate widget " + widget);
            final String name = widget instanceof DisplayModel ? Messages.DisplayProperties
                                                               : widget.getType() + " '" + widget.getName() + "'";
            new WidgetHistoryView(model, key, name, WidgetKeys.write(model));
        }
        catch (Exception ex)
        {
            showError(Messages.GitWidgetHistory, ex);
        }
    }

    /** Show history of the display file
     *  @param model Display model
     */
    static void showDisplayHistory(final DisplayModel model)
    {
        final File file = WidgetKeys.getFile(model);
        if (file == null)
            showError(Messages.GitDisplayHistory, new Exception(Messages.NoDisplayFile));
        else
            GitView.showFileHistory(file);
    }

    /** Compare saved display file with HEAD
     *  @param model Display model
     */
    static void compareWithHead(final DisplayModel model)
    {
        try
        {
            final DisplayFile display = open(model);
            final GitActions actions = new GitActions(display::git, DockPane.getActiveDockPane(), null);
            actions.setOwned(display.git());
            actions.compareRef("HEAD", List.of(display.path()));
        }
        catch (Exception ex)
        {
            showError(Messages.GitDisplayCompare, ex);
        }
    }

    /** Select a widget in the editor that has the display open
     *  @param file Display file
     *  @param key Widget key
     *  @return Was the widget selected?
     */
    static boolean selectWidget(final File file, final String key)
    {
        final DisplayEditorInstance editor = findEditor(file);
        if (editor == null)
            return false;
        final Widget widget = WidgetKeys.of(editor.getDisplayEditor().getModel()).get(key);
        if (widget == null)
            return false;
        editor.getDisplayEditor().getWidgetSelectionHandler().setSelection(List.of(widget));
        for (Stage stage : DockStage.getDockStages())
            for (DockPane pane : DockStage.getDockPanes(stage))
                for (DockItem item : pane.getDockItems())
                    if ((Object) item.getApplication() == editor)
                        item.select();
        return true;
    }
}
