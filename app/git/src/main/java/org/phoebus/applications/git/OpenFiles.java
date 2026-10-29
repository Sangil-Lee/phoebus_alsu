package org.phoebus.applications.git;

import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;

import org.csstudio.display.builder.editor.app.DisplayEditorApplication;
import org.csstudio.display.builder.editor.app.DisplayEditorInstance;
import org.csstudio.display.builder.runtime.app.DisplayRuntimeInstance;
import org.phoebus.framework.spi.AppDescriptor;
import org.phoebus.framework.spi.AppInstance;
import org.phoebus.framework.spi.AppResourceDescriptor;
import org.phoebus.framework.workbench.ApplicationService;
import org.phoebus.ui.application.ApplicationLauncherService;
import org.phoebus.ui.docking.DockItem;
import org.phoebus.ui.docking.DockItemWithInput;
import org.phoebus.ui.docking.DockPane;
import org.phoebus.ui.docking.DockStage;

import javafx.stage.Stage;

/** Files that are open in phoebus tabs, and opening files
 *
 *  <p>Call on the UI thread.
 */
@SuppressWarnings("nls")
class OpenFiles
{
    /** File open in a tab
     *  @param item Tab
     *  @param file File shown in the tab
     */
    record OpenFile(DockItemWithInput item, File file) {}

    /** @return Files of all tabs in all windows */
    static List<OpenFile> list()
    {
        final List<OpenFile> result = new ArrayList<>();
        for (Stage stage : DockStage.getDockStages())
            for (DockPane pane : DockStage.getDockPanes(stage))
                for (DockItem item : pane.getDockItems())
                    if (item instanceof DockItemWithInput with_input  &&
                        with_input.getInput() != null  &&
                        "file".equals(with_input.getInput().getScheme()))
                        result.add(new OpenFile(with_input, new File(with_input.getInput().getPath())));
        return result;
    }

    /** @param directory Directory
     *  @return Files open in tabs that are inside the directory
     */
    static List<OpenFile> list(final File directory)
    {
        final String prefix = directory.getAbsolutePath() + File.separator;
        return list().stream()
                     .filter(open -> open.file().getAbsolutePath().startsWith(prefix))
                     .toList();
    }

    /** @param name File name
     *  @return Can the file be opened in the display editor?
     */
    static boolean isDisplay(final String name)
    {
        return name.endsWith(".bob")  ||  name.endsWith(".opi")  ||  name.endsWith(".bcf");
    }

    /** Open a file
     *  @param file File
     *  @param editor Open display in the editor, otherwise with the default application, i.e. runtime for displays
     */
    static void open(final File file, final boolean editor)
    {
        if (editor)
        {
            final AppDescriptor app = ApplicationService.findApplication(DisplayEditorApplication.NAME);
            if (app instanceof AppResourceDescriptor resource_app)
            {
                resource_app.create(file.toURI());
                return;
            }
        }
        ApplicationLauncherService.openFile(file, false, null);
    }

    /** @param files Files
     *  @return Time stamp and size of each file, to detect changes
     */
    static Map<File, String> stamp(final Collection<File> files)
    {
        final Map<File, String> stamps = new HashMap<>();
        for (File file : files)
            stamps.put(file, file.exists() ? file.lastModified() + ":" + file.length() : "missing");
        return stamps;
    }

    /** Reload a tab from its file
     *
     *  <p>Displays are re-loaded in place, keeping for example their macros.
     *  Other tabs are closed and opened again.
     *
     *  @param open Open file
     */
    static void reload(final OpenFile open)
    {
        final AppInstance app = open.item().getApplication();
        try
        {
            if (app instanceof DisplayRuntimeInstance runtime)
                runtime.reload();
            else if (app instanceof DisplayEditorInstance editor)
                editor.reloadDisplay();
            else if (app != null  &&  app.getAppDescriptor() instanceof AppResourceDescriptor resource_app)
            {
                open.item().close();
                resource_app.create(open.file().toURI());
            }
        }
        catch (Exception ex)
        {
            GitApp.logger.log(Level.WARNING, "Cannot reload " + open.file(), ex);
        }
    }
}
