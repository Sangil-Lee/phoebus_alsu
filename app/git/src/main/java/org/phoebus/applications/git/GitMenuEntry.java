package org.phoebus.applications.git;

import org.phoebus.framework.workbench.ApplicationService;
import org.phoebus.ui.javafx.ImageCache;
import org.phoebus.ui.spi.MenuEntry;

import javafx.scene.image.Image;

@SuppressWarnings("nls")
public class GitMenuEntry implements MenuEntry
{
    @Override
    public String getName()
    {
        return GitApp.DisplayName;
    }

    @Override
    public String getMenuPath()
    {
        return Messages.MenuPath;
    }

    @Override
    public Image getIcon()
    {
        return ImageCache.getImage(GitApp.class, "/icons/git.png");
    }

    @Override
    public Void call() throws Exception
    {
        ApplicationService.createInstance(GitApp.Name);
        return null;
    }
}
