package org.phoebus.applications.git;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.csstudio.display.builder.model.ChildrenProperty;
import org.csstudio.display.builder.model.DisplayModel;
import org.csstudio.display.builder.model.Widget;
import org.csstudio.display.builder.model.persist.ModelReader;
import org.csstudio.display.builder.model.persist.ModelWriter;
import org.csstudio.display.builder.model.widgets.TabsWidget;
import org.csstudio.display.builder.model.widgets.TabsWidget.TabItemProperty;

/** Keys of widgets in a display model, same as used by {@link BobDiff}
 *
 *  <p>Key: Parent key, then "/type:name", with "#n" for the n-th widget of same type and name.
 */
@SuppressWarnings("nls")
class WidgetKeys
{
    /** @param model Display model
     *  @return Widgets by key, in the order of the display file
     */
    static Map<String, Widget> of(final DisplayModel model)
    {
        final Map<String, Widget> result = new LinkedHashMap<>();
        add(model, "", result);
        return result;
    }

    private static void add(final Widget parent, final String parent_key, final Map<String, Widget> result)
    {
        final Map<String, Integer> count = new HashMap<>();
        for (Widget widget : getChildren(parent))
        {
            final String id = widget.getType() + ":" + widget.getName();
            final int n = count.merge(id, 1, Integer::sum);
            final String key = parent_key + "/" + id + (n > 1 ? "#" + n : "");
            result.put(key, widget);
            add(widget, key, result);
        }
    }

    /** @param widget Widget
     *  @return Child widgets as written to the file: Children of groups, ..., of all tabs
     */
    private static List<Widget> getChildren(final Widget widget)
    {
        final ChildrenProperty children = ChildrenProperty.getChildren(widget);
        if (children != null)
            return children.getValue();
        if (widget instanceof TabsWidget tabs)
        {
            final List<Widget> result = new ArrayList<>();
            for (TabItemProperty tab : tabs.propTabs().getValue())
                result.addAll(tab.children().getValue());
            return result;
        }
        return List.of();
    }

    /** @param widget Widget
     *  @return Key of the widget, <code>null</code> if not found in its model
     */
    static String keyOf(final Widget widget) throws Exception
    {
        if (widget instanceof DisplayModel)
            return "";
        for (Map.Entry<String, Widget> entry : of(widget.getDisplayModel()).entrySet())
            if (entry.getValue() == widget)
                return entry.getKey();
        return null;
    }

    /** @param model Display model
     *  @return File of the model, <code>null</code> if not known
     */
    static File getFile(final DisplayModel model)
    {
        final String name = model.getUserData(DisplayModel.USER_DATA_INPUT_FILE);
        if (name == null  ||  name.isBlank())
            return null;
        if (name.startsWith("file:"))
            return new File(URI.create(name));
        if (name.contains("://"))
            return null;
        return new File(name);
    }

    /** @param model Display model
     *  @return Model written as display file
     */
    static byte[] write(final DisplayModel model) throws Exception
    {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ModelWriter writer = new ModelWriter(out))
        {
            writer.writeModel(model);
        }
        return out.toByteArray();
    }

    /** Read and write a display file, so that it can be compared with a model written the same way
     *  @param content Display file
     *  @return Normalized content, or the original content if it cannot be read
     */
    static byte[] normalize(final byte[] content)
    {
        if (content.length == 0)
            return content;
        try
        {
            return write(new ModelReader(new ByteArrayInputStream(content)).readModel());
        }
        catch (Exception ex)
        {
            return content;
        }
    }
}
