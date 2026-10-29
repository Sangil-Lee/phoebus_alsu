package org.phoebus.applications.git;

import java.io.ByteArrayInputStream;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.HashSet;
import java.util.TreeSet;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

/** Compare two versions of a display file (*.bob) by widget and property
 *
 *  <p>Widgets are matched by their parent, type and name,
 *  then their properties are compared one by one.
 *  Easier to read than a text diff of the XML.
 */
@SuppressWarnings("nls")
class BobDiff
{
    /** Kind of change */
    enum Kind { ADDED, REMOVED, CHANGED }

    /** One change
     *  @param widget Description of the widget, for example "textupdate 'temp'", or the display
     *  @param property Changed property, <code>null</code> for added or removed widget
     *  @param old_value Old value, <code>null</code> if property was not set (default)
     *  @param new_value New value, <code>null</code> if property is no longer set (default)
     *  @param key Key of the widget: Parent key, type, name and number among widgets of same type and name,
     *             for example "/group:Box/textupdate:temp#2", empty for the display
     */
    record Change(Kind kind, String widget, String property, String old_value, String new_value, String key) {}

    /** Widget found in a display file */
    private record WidgetInfo(String description, Map<String, String> properties) {}

    /** Element that holds widgets, it's not a property */
    private static final String WIDGET = "widget";

    /** @param name Name of a display or widget file
     *  @return Can this file be compared by widget?
     */
    static boolean supports(final String name)
    {
        return name.endsWith(".bob")  ||  name.endsWith(".bcf");
    }

    /** @param old_content Old file content, empty if the file was added
     *  @param new_content New file content, empty if the file was removed
     *  @return Changes, display properties first, then widgets in order of the new file
     *  @throws Exception if a file cannot be parsed
     */
    static List<Change> compare(final byte[] old_content, final byte[] new_content) throws Exception
    {
        final Map<String, WidgetInfo> old_widgets = parse(old_content);
        final Map<String, WidgetInfo> new_widgets = parse(new_content);
        final List<Change> changes = new ArrayList<>();

        // Widgets in the new file: Added or changed
        for (Map.Entry<String, WidgetInfo> entry : new_widgets.entrySet())
        {
            final WidgetInfo after = entry.getValue();
            final WidgetInfo before = old_widgets.get(entry.getKey());
            if (before == null)
                changes.add(new Change(Kind.ADDED, after.description(), null, null, null, entry.getKey()));
            else
                for (String property : new TreeSet<>(union(before.properties().keySet(), after.properties().keySet())))
                {
                    final String old_value = before.properties().get(property);
                    final String new_value = after.properties().get(property);
                    if (! Objects.equals(old_value, new_value))
                        changes.add(new Change(Kind.CHANGED, after.description(), property, old_value, new_value, entry.getKey()));
                }
        }
        // Widgets only in the old file: Removed
        for (Map.Entry<String, WidgetInfo> entry : old_widgets.entrySet())
            if (! new_widgets.containsKey(entry.getKey()))
                changes.add(new Change(Kind.REMOVED, entry.getValue().description(), null, null, null, entry.getKey()));
        return changes;
    }

    private static Set<String> union(final Set<String> a, final Set<String> b)
    {
        final Set<String> result = new HashSet<>(a);
        result.addAll(b);
        return result;
    }

    /** @param content File content
     *  @return Display (key "") and widgets by key, in file order
     */
    private static Map<String, WidgetInfo> parse(final byte[] content) throws Exception
    {
        final Map<String, WidgetInfo> widgets = new LinkedHashMap<>();
        if (content.length == 0)
            return widgets;
        final Element root = createBuilder().parse(new ByteArrayInputStream(content)).getDocumentElement();
        widgets.put("", new WidgetInfo(Messages.DisplayProperties, getProperties(root)));
        addWidgets(root, "", "", widgets);
        return widgets;
    }

    static DocumentBuilder createBuilder() throws Exception
    {
        // No external entities, no DTD
        final DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setExpandEntityReferences(false);
        return factory.newDocumentBuilder();
    }

    /** Add widgets below an element, also widgets inside groups, tabs, ...
     *  @param parent Element that may contain widgets, possibly nested in property elements
     *  @param parent_key Key of the parent widget
     *  @param location Description of the parent widget, empty for the display
     *  @param widgets Collected widgets
     */
    private static void addWidgets(final Element parent, final String parent_key, final String location,
                                   final Map<String, WidgetInfo> widgets) throws Exception
    {
        // Count widgets with same type and name in this parent, to tell them apart
        final Map<String, Integer> count = new HashMap<>();
        for (Element widget : findWidgets(parent))
        {
            final String type = widget.getAttribute("type");
            final String name = getText(widget, "name");
            final String id = type + ":" + name;
            final int n = count.merge(id, 1, Integer::sum);
            final String key = parent_key + "/" + id + (n > 1 ? "#" + n : "");
            final String description = type + " '" + name + "'" + (n > 1 ? " #" + n : "")
                                     + (location.isEmpty() ? "" : "  (" + location + ")");
            widgets.put(key, new WidgetInfo(description, getProperties(widget)));
            addWidgets(widget, key, type + " '" + name + "'", widgets);
        }
    }

    /** @param parent Element
     *  @return Widgets directly below the element, or below its property elements (tabs, ...)
     *          but not below other widgets
     */
    static List<Element> findWidgets(final Element parent)
    {
        final List<Element> result = new ArrayList<>();
        for (Node node = parent.getFirstChild(); node != null; node = node.getNextSibling())
            if (node instanceof Element element)
            {
                if (WIDGET.equals(element.getTagName()))
                    result.add(element);
                else
                    result.addAll(findWidgets(element));
            }
        return result;
    }

    /** @return Text of the first direct child element with that name, empty if none */
    static String getText(final Element parent, final String name)
    {
        for (Node node = parent.getFirstChild(); node != null; node = node.getNextSibling())
            if (node instanceof Element element  &&  name.equals(element.getTagName()))
                return element.getTextContent().trim();
        return "";
    }

    /** @param widget Widget or display element
     *  @return Property name and value of each property element, without nested widgets
     */
    private static Map<String, String> getProperties(final Element widget) throws Exception
    {
        final Map<String, String> properties = new LinkedHashMap<>();
        for (Node node = widget.getFirstChild(); node != null; node = node.getNextSibling())
            if (node instanceof Element element  &&  ! WIDGET.equals(element.getTagName()))
            {
                final String value = getValue(element);
                // Element that only holds widgets (group children, ...) is not a property
                if (! value.isEmpty()  ||  element.getAttributes().getLength() > 0  ||  ! hasWidgets(element))
                    properties.put(element.getTagName(), value);
            }
        return properties;
    }

    static boolean hasWidgets(final Element element)
    {
        return ! findWidgets(element).isEmpty();
    }

    /** @param property Property element
     *  @return Text value, or compact XML for structured values, without nested widgets
     */
    static String getValue(final Element property) throws Exception
    {
        boolean structured = property.getAttributes().getLength() > 0;
        for (Node node = property.getFirstChild(); node != null; node = node.getNextSibling())
            if (node instanceof Element)
                structured = true;
        if (! structured)
            return property.getTextContent().trim();

        // Copy without nested widgets, write as one line
        final Document doc = createBuilder().newDocument();
        final Element copy = (Element) doc.importNode(property, true);
        removeWidgets(copy);
        doc.appendChild(copy);
        final Transformer transformer = TransformerFactory.newInstance().newTransformer();
        transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "yes");
        transformer.setOutputProperty(OutputKeys.INDENT, "no");
        final StringWriter out = new StringWriter();
        transformer.transform(new DOMSource(doc), new StreamResult(out));
        final String xml = out.toString().replaceAll(">\\s+<", "><").trim();
        // Show content of the property element, not the element itself
        final int start = xml.indexOf('>') + 1;
        final int end = xml.lastIndexOf("</");
        return start > 0  &&  end >= start  ?  xml.substring(start, end).trim()  :  xml;
    }

    private static void removeWidgets(final Element element)
    {
        Node node = element.getFirstChild();
        while (node != null)
        {
            final Node next = node.getNextSibling();
            if (node instanceof Element child)
            {
                if (WIDGET.equals(child.getTagName()))
                    element.removeChild(child);
                else
                    removeWidgets(child);
            }
            node = next;
        }
    }

    /** Maximum length of a value in the formatted output */
    private static final int MAX_VALUE = 160;

    private static String shorten(final String value)
    {
        if (value == null)
            return Messages.DefaultValue;
        final String line = value.replace('\n', ' ');
        return line.length() > MAX_VALUE ? line.substring(0, MAX_VALUE) + "…" : line;
    }

    /** @param old_content Old file content, empty if the file was added
     *  @param new_content New file content, empty if the file was removed
     *  @return Lines that describe the changes: "+ ..." added, "- ..." removed, "~ ..." changed
     */
    static List<String> format(final byte[] old_content, final byte[] new_content)
    {
        return format(old_content, new_content, null);
    }

    /** @param change Change
     *  @return Line that describes the change
     */
    static String format(final Change change)
    {
        return switch (change.kind())
        {
            case ADDED   -> "+ " + Messages.WidgetAdded + ": " + change.widget();
            case REMOVED -> "- " + Messages.WidgetRemoved + ": " + change.widget();
            case CHANGED -> "~ " + change.widget() + "   " + change.property() + ": "
                                 + shorten(change.old_value()) + "  →  " + shorten(change.new_value());
        };
    }

    /** @param old_content Old file content, empty if the file was added
     *  @param new_content New file content, empty if the file was removed
     *  @param keys Receives the widget key for each line, may be <code>null</code>
     *  @return Lines that describe the changes: "+ ..." added, "- ..." removed, "~ ..." changed
     */
    static List<String> format(final byte[] old_content, final byte[] new_content, final List<String> keys)
    {
        final List<String> lines = new ArrayList<>();
        final List<Change> changes;
        try
        {
            changes = compare(old_content, new_content);
        }
        catch (Exception ex)
        {
            lines.add("! " + Messages.CannotParse + ": " + ex.getMessage());
            if (keys != null)
                keys.add(null);
            return lines;
        }
        if (changes.isEmpty())
        {
            lines.add(Messages.NoWidgetChanges);
            if (keys != null)
                keys.add(null);
        }
        for (Change change : changes)
        {
            lines.add(format(change));
            if (keys != null)
                keys.add(change.key());
        }
        return lines;
    }
}
