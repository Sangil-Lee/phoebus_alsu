package org.phoebus.applications.git;

import org.phoebus.framework.workbench.ApplicationService;
import org.phoebus.ui.javafx.ImageCache;
import org.phoebus.ui.spi.ToolbarEntry;

import javafx.scene.image.Image;

@SuppressWarnings("nls")
public class GitToolbarEntry implements ToolbarEntry
{
    @Override
    public String getName()
    {
        return GitApp.DisplayName;
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

    /**
     * DO NOT CHANGE RETURN VALUE!
     * @return The unique id of this {@link ToolbarEntry}.
     */
    @Override
    public String getId()
    {
        return "Git";
    }
}
