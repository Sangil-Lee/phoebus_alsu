package org.phoebus.applications.git;

import java.io.File;

import org.phoebus.framework.selection.Selection;
import org.phoebus.ui.javafx.ImageCache;
import org.phoebus.ui.spi.ContextMenuEntry;

import javafx.scene.image.Image;

/** Context menu entry for files, for example in the file browser: Show the file in the Git view */
public class GitShowInGitMenuEntry implements ContextMenuEntry
{
    @Override
    public String getName()
    {
        return Messages.ShowInGitMenu;
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
        selection.getSelections().stream()
                 .filter(File.class::isInstance)
                 .map(File.class::cast)
                 .findFirst()
                 .ifPresent(GitView::showInGit);
    }
}
