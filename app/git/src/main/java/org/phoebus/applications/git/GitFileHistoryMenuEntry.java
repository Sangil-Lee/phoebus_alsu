package org.phoebus.applications.git;

import java.io.File;

import org.phoebus.framework.selection.Selection;
import org.phoebus.ui.javafx.ImageCache;
import org.phoebus.ui.spi.ContextMenuEntry;

import javafx.scene.image.Image;

/** Context menu entry for files, for example in the file browser: Show git history and blame */
@SuppressWarnings("nls")
public class GitFileHistoryMenuEntry implements ContextMenuEntry
{
    @Override
    public String getName()
    {
        return Messages.GitFileHistoryMenu;
    }

    @Override
    public Image getIcon()
    {
        return ImageCache.getImage(GitApp.class, "/icons/git.png");
    }

    @Override
    public Class<?> getSupportedType()
    {
        return File.class;
    }

    @Override
    public void call(final Selection selection) throws Exception
    {
        for (File file : selection.getSelections().stream().filter(File.class::isInstance).map(File.class::cast).toList())
            // History is for files, a directory is shown in the Git view
            if (file.isDirectory())
                GitView.showInGit(file);
            else
                GitView.showFileHistory(file);
    }
}
