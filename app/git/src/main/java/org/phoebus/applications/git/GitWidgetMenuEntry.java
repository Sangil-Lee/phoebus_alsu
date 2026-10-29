package org.phoebus.applications.git;

import java.util.List;

import org.csstudio.display.builder.model.DisplayModel;
import org.csstudio.display.builder.model.Widget;
import org.phoebus.framework.selection.Selection;
import org.phoebus.ui.javafx.ImageCache;
import org.phoebus.ui.spi.ContextMenuEntry;

import javafx.scene.image.Image;

/** Context menu entries of the display editor for widgets in a git repository */
public abstract class GitWidgetMenuEntry implements ContextMenuEntry
{
    @Override
    public Image getIcon()
    {
        return ImageCache.getImage(GitApp.class, "/icons/git.png");
    }

    @Override
    public Class<?> getSupportedType()
    {
        return Widget.class;
    }

    @Override
    public void call(final Selection selection) throws Exception
    {
        final List<Widget> widgets = selection.getSelections().stream()
                                              .filter(Widget.class::isInstance)
                                              .map(Widget.class::cast)
                                              .toList();
        if (! widgets.isEmpty())
            call(widgets);
    }

    /** @param widgets Selected widgets, or the display model */
    protected abstract void call(List<Widget> widgets) throws Exception;

    /** Select widgets that differ from HEAD */
    public static class SelectChanged extends GitWidgetMenuEntry
    {
        @Override
        public String getName()
        {
            return Messages.GitSelectChanged;
        }

        @Override
        protected void call(final List<Widget> widgets) throws Exception
        {
            GitEditorSupport.selectChangedWidgets(widgets.get(0).getDisplayModel());
        }
    }

    /** History of the selected widget */
    public static class WidgetHistory extends GitWidgetMenuEntry
    {
        @Override
        public String getName()
        {
            return Messages.GitWidgetHistory;
        }

        @Override
        protected void call(final List<Widget> widgets) throws Exception
        {
            GitEditorSupport.showWidgetHistory(widgets.get(0));
        }
    }

    /** History of the display file */
    public static class DisplayHistory extends GitWidgetMenuEntry
    {
        @Override
        public String getName()
        {
            return Messages.GitDisplayHistory;
        }

        @Override
        protected void call(final List<Widget> widgets) throws Exception
        {
            GitEditorSupport.showDisplayHistory(widgets.get(0).getDisplayModel());
        }
    }

    /** Compare saved display with HEAD */
    public static class CompareWithHead extends GitWidgetMenuEntry
    {
        @Override
        public String getName()
        {
            return Messages.GitDisplayCompare;
        }

        @Override
        protected void call(final List<Widget> widgets) throws Exception
        {
            final DisplayModel model = widgets.get(0).getDisplayModel();
            GitEditorSupport.compareWithHead(model);
        }
    }
}
