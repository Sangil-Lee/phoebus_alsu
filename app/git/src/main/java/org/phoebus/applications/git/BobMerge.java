package org.phoebus.applications.git;

import java.io.ByteArrayInputStream;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;

import org.csstudio.display.builder.model.persist.ModelReader;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.Text;

/** Three-way merge of display files by widget and property
 *
 *  <p>Widgets are matched by the same key as {@link BobDiff}.
 *  A property that only one side changed is taken from that side.
 *  A property that both sides changed differently is a conflict,
 *  as is a widget that one side removed while the other changed it.
 *
 *  <p>The result is 'ours' with the changes from 'theirs' applied,
 *  keeping the formatting and widget order of 'ours'.
 */
@SuppressWarnings("nls")
class BobMerge
{
    /** How a conflict is resolved */
    enum Choice { UNRESOLVED, OURS, THEIRS }

    /** Change of the 'ours' document */
    private interface Operation
    {
        /** @param doc Document to change, 'ours'
         *  @param widgets Widgets of that document by key
         */
        void apply(Document doc, Map<String, Element> widgets) throws Exception;
    }

    /** Conflict
     *  @param key Widget key
     *  @param widget Description of the widget
     *  @param property Property, <code>null</code> if the widget was removed on one side
     *  @param ours Our value or description
     *  @param theirs Their value or description
     */
    static class Conflict
    {
        final String key, widget, property, ours, theirs;
        private final Operation use_theirs;
        Choice choice = Choice.UNRESOLVED;

        Conflict(final String key, final String widget, final String property,
                 final String ours, final String theirs, final Operation use_theirs)
        {
            this.key = key;
            this.widget = widget;
            this.property = property;
            this.ours = ours;
            this.theirs = theirs;
            this.use_theirs = use_theirs;
        }

        @Override
        public String toString()
        {
            return widget + (property == null ? "" : "  " + property) + ": " + ours + " / " + theirs;
        }
    }

    /** Widget in a parsed file
     *  @param element Widget element, or the display element
     *  @param description Description
     *  @param parent Key of parent widget
     *  @param properties Property elements by name
     */
    private record WidgetInfo(Element element, String description, String parent, Map<String, Element> properties)
    {
        String value(final String property) throws Exception
        {
            final Element element = properties.get(property);
            return element == null ? null : BobDiff.getValue(element);
        }
    }

    private final byte[] ours;
    private final List<Conflict> conflicts = new ArrayList<>();
    private final List<String> automatic = new ArrayList<>();
    private final List<Operation> operations = new ArrayList<>();

    /** @param base Content of common ancestor, empty if none
     *  @param ours Our content
     *  @param theirs Their content
     *  @throws Exception if the files cannot be merged by widget
     */
    BobMerge(final byte[] base, final byte[] ours, final byte[] theirs) throws Exception
    {
        this.ours = ours;
        final Map<String, WidgetInfo> b = parse(base);
        final Map<String, WidgetInfo> o = parse(ours);
        final Map<String, WidgetInfo> t = parse(theirs);
        if (o.isEmpty()  ||  t.isEmpty())
            throw new Exception(Messages.BobMergeDeleted);

        final Set<String> keys = new LinkedHashSet<>(o.keySet());
        keys.addAll(t.keySet());
        keys.addAll(b.keySet());
        for (String key : keys)
        {
            final WidgetInfo wb = b.get(key), wo = o.get(key), wt = t.get(key);
            if (wo != null  &&  wt != null)
                mergeProperties(key, wb, wo, wt);
            else if (wo == null  &&  wt != null)
            {
                if (wb == null)
                {   // Added by theirs, unless included in an added parent
                    if (o.containsKey(wt.parent())  ||  wt.parent().isEmpty())
                    {
                        automatic.add(Messages.WidgetAdded + ": " + wt.description());
                        operations.add(insert(key, wt, t));
                    }
                }
                else if (! sameProperties(wb, wt))
                    conflicts.add(new Conflict(key, wt.description(), null,
                                               Messages.WidgetRemoved, Messages.MergeWidgetChanged, insert(key, wt, t)));
                // else: Removed by ours, unchanged by theirs
            }
            else if (wo != null  &&  wt == null  &&  wb != null)
            {
                if (sameProperties(wb, wo))
                {   // Removed by theirs, unchanged by ours, unless parent is also removed
                    if (! b.containsKey(wo.parent())  ||  t.containsKey(wo.parent())  ||  wo.parent().isEmpty())
                    {
                        automatic.add(Messages.WidgetRemoved + ": " + wo.description());
                        operations.add(remove(key));
                    }
                }
                else
                    conflicts.add(new Conflict(key, wo.description(), null,
                                               Messages.MergeWidgetChanged, Messages.WidgetRemoved, remove(key)));
            }
            // else: Only in ours, or removed by both
        }
    }

    /** @param key Widget key
     *  @param wb Base, may be <code>null</code> if both sides added the widget
     *  @param wo Ours
     *  @param wt Theirs
     */
    private void mergeProperties(final String key, final WidgetInfo wb, final WidgetInfo wo, final WidgetInfo wt) throws Exception
    {
        final Set<String> names = new LinkedHashSet<>(wo.properties().keySet());
        names.addAll(wt.properties().keySet());
        if (wb != null)
            names.addAll(wb.properties().keySet());
        for (String name : names)
        {
            final String vb = wb == null ? null : wb.value(name);
            final String vo = wo.value(name);
            final String vt = wt.value(name);
            if (Objects.equals(vo, vt)  ||  Objects.equals(vt, vb))
                continue;
            // Properties that contain widgets, like the tabs of a tab widget, would also replace the widgets
            if (containsWidgets(wo.properties().get(name))  ||  containsWidgets(wt.properties().get(name)))
                throw new Exception(Messages.BobMergeUnsupported + ": " + wo.description() + " " + name);
            final Element theirs = wt.properties().get(name);
            final Operation operation = setProperty(key, name, theirs);
            if (Objects.equals(vo, vb))
            {
                automatic.add(wo.description() + "  " + name + ": " + shorten(vo) + " → " + shorten(vt));
                operations.add(operation);
            }
            else
                conflicts.add(new Conflict(key, wo.description(), name, shorten(vo), shorten(vt), operation));
        }
    }

    private static boolean containsWidgets(final Element element)
    {
        return element != null  &&  BobDiff.hasWidgets(element);
    }

    private static String shorten(final String value)
    {
        if (value == null)
            return Messages.DefaultValue;
        final String line = value.replace('\n', ' ');
        return line.length() > 100 ? line.substring(0, 100) + "…" : line;
    }

    private static boolean sameProperties(final WidgetInfo a, final WidgetInfo b) throws Exception
    {
        final Set<String> names = new LinkedHashSet<>(a.properties().keySet());
        names.addAll(b.properties().keySet());
        for (String name : names)
            if (! Objects.equals(a.value(name), b.value(name)))
                return false;
        return true;
    }

    /** @return Conflicts */
    List<Conflict> getConflicts()
    {
        return conflicts;
    }

    /** @return Descriptions of changes taken from 'theirs' without conflict */
    List<String> getAutomatic()
    {
        return automatic;
    }

    /** @return Number of unresolved conflicts */
    int getUnresolved()
    {
        return (int) conflicts.stream().filter(c -> c.choice == Choice.UNRESOLVED).count();
    }

    /** @return Merged display file
     *  @throws Exception if conflicts are unresolved, or the result cannot be created or read
     */
    byte[] getResult() throws Exception
    {
        if (getUnresolved() > 0)
            throw new Exception(Messages.SaveUnresolved.formatted(getUnresolved()));
        final Document doc = BobDiff.createBuilder().parse(new ByteArrayInputStream(ours));
        final List<Operation> todo = new ArrayList<>(operations);
        for (Conflict conflict : conflicts)
            if (conflict.choice == Choice.THEIRS)
                todo.add(conflict.use_theirs);
        // Widgets are inserted in the order of 'theirs', so a widget's predecessor is already inserted
        for (Operation operation : todo)
            operation.apply(doc, widgets(doc));

        final byte[] result = write(doc);
        // Check that the display builder can read it
        new ModelReader(new ByteArrayInputStream(result)).readModel();
        return result;
    }

    /** @param doc Document
     *  @return Widget elements by key
     */
    private static Map<String, Element> widgets(final Document doc)
    {
        final Map<String, Element> result = new HashMap<>();
        result.put("", doc.getDocumentElement());
        addElements(doc.getDocumentElement(), "", result);
        return result;
    }

    private static void addElements(final Element parent, final String parent_key, final Map<String, Element> result)
    {
        final Map<String, Integer> count = new HashMap<>();
        for (Element widget : BobDiff.findWidgets(parent))
        {
            final String id = widget.getAttribute("type") + ":" + BobDiff.getText(widget, "name");
            final int n = count.merge(id, 1, Integer::sum);
            final String key = parent_key + "/" + id + (n > 1 ? "#" + n : "");
            result.put(key, widget);
            addElements(widget, key, result);
        }
    }

    /** @param doc Document
     *  @return XML with the original declaration of 'ours'
     */
    private byte[] write(final Document doc) throws Exception
    {
        final Transformer transformer = TransformerFactory.newInstance().newTransformer();
        transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "yes");
        transformer.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
        final StringWriter out = new StringWriter();
        transformer.transform(new DOMSource(doc), new StreamResult(out));
        final String text = new String(ours, StandardCharsets.UTF_8);
        String declaration = "";
        if (text.startsWith("<?xml"))
        {
            int end = text.indexOf("?>") + 2;
            while (end < text.length()  &&  Character.isWhitespace(text.charAt(end)))
                ++end;
            declaration = text.substring(0, end);
        }
        String body = out.toString();
        if (text.endsWith("\n")  &&  ! body.endsWith("\n"))
            body += "\n";
        return (declaration + body).getBytes(StandardCharsets.UTF_8);
    }

    // --- Parsing -----------------------------------------------------------------

    /** @param content File content
     *  @return Display (key "") and widgets by key, in file order
     */
    private static Map<String, WidgetInfo> parse(final byte[] content) throws Exception
    {
        if (content.length == 0)
            return new LinkedHashMap<>();
        return parse(BobDiff.createBuilder().parse(new ByteArrayInputStream(content)).getDocumentElement());
    }

    private static Map<String, WidgetInfo> parse(final Element root) throws Exception
    {
        final Map<String, WidgetInfo> widgets = new LinkedHashMap<>();
        widgets.put("", new WidgetInfo(root, Messages.DisplayProperties, "", getProperties(root)));
        addWidgets(root, "", "", widgets);
        return widgets;
    }

    /** Same as {@link BobDiff}, keeping the elements */
    private static void addWidgets(final Element parent, final String parent_key, final String location,
                                   final Map<String, WidgetInfo> widgets) throws Exception
    {
        final Map<String, Integer> count = new HashMap<>();
        for (Element widget : BobDiff.findWidgets(parent))
        {
            final String type = widget.getAttribute("type");
            final String name = BobDiff.getText(widget, "name");
            final String id = type + ":" + name;
            final int n = count.merge(id, 1, Integer::sum);
            final String key = parent_key + "/" + id + (n > 1 ? "#" + n : "");
            final String description = type + " '" + name + "'" + (n > 1 ? " #" + n : "")
                                     + (location.isEmpty() ? "" : "  (" + location + ")");
            widgets.put(key, new WidgetInfo(widget, description, parent_key, getProperties(widget)));
            addWidgets(widget, key, type + " '" + name + "'", widgets);
        }
    }

    private static Map<String, Element> getProperties(final Element widget) throws Exception
    {
        final Map<String, Element> properties = new LinkedHashMap<>();
        for (Node node = widget.getFirstChild(); node != null; node = node.getNextSibling())
            if (node instanceof Element element  &&  ! "widget".equals(element.getTagName()))
            {
                // Element that only holds widgets is not a property
                if (! BobDiff.getValue(element).isEmpty()  ||  element.getAttributes().getLength() > 0  ||
                    ! BobDiff.hasWidgets(element))
                    properties.put(element.getTagName(), element);
            }
        return properties;
    }

    // --- Operations --------------------------------------------------------------

    /** @param key Widget key
     *  @param name Property name
     *  @param theirs Their property element, <code>null</code> to remove the property
     */
    private static Operation setProperty(final String key, final String name, final Element theirs)
    {
        return (doc, widgets) ->
        {
            final Element widget = widgets.get(key);
            if (widget == null)
                throw new Exception("Cannot locate " + key);
            Element existing = null;
            for (Node node = widget.getFirstChild(); node != null; node = node.getNextSibling())
                if (node instanceof Element element  &&  name.equals(element.getTagName()))
                    existing = element;
            if (theirs == null)
            {
                if (existing != null)
                    removeWithSpace(existing);
                return;
            }
            final Node copy = doc.importNode(theirs, true);
            if (existing != null)
                widget.replaceChild(copy, existing);
            else
            {
                // Add after the last property, before nested widgets
                Node last = null;
                for (Node node = widget.getFirstChild(); node != null; node = node.getNextSibling())
                    if (node instanceof Element element  &&  ! "widget".equals(element.getTagName()))
                        last = node;
                insertAfter(widget, copy, last);
            }
        };
    }

    private static Operation remove(final String key)
    {
        return (doc, widgets) ->
        {
            final Element widget = widgets.get(key);
            if (widget != null)
                removeWithSpace(widget);
        };
    }

    /** @param key Key of widget to insert
     *  @param widget Widget in 'theirs'
     *  @param theirs All widgets of 'theirs'
     */
    private static Operation insert(final String key, final WidgetInfo widget, final Map<String, WidgetInfo> theirs)
    {
        // Path from parent widget to the element that holds the widget, e.g. tabs/tab[1]/children for a tab
        final Element parent = theirs.get(widget.parent()).element();
        final List<String> path = new ArrayList<>();
        for (Node node = widget.element().getParentNode(); node != parent; node = node.getParentNode())
            path.add(0, ((Element) node).getTagName() + "#" + index((Element) node));
        // Preceding widget in the same container
        String previous = null;
        for (Node node = widget.element().getPreviousSibling(); node != null  &&  previous == null; node = node.getPreviousSibling())
            if (node instanceof Element element  &&  "widget".equals(element.getTagName()))
                for (Map.Entry<String, WidgetInfo> entry : theirs.entrySet())
                    if (entry.getValue().element() == element)
                        previous = entry.getKey();
        final String after = previous;
        return (doc, widgets) ->
        {
            Element container = widgets.get(widget.parent());
            if (container == null)
                throw new Exception(Messages.BobMergeUnsupported + ": " + widget.description());
            for (String step : path)
            {
                final String[] tag_index = step.split("#");
                container = child(container, tag_index[0], Integer.parseInt(tag_index[1]));
                if (container == null)
                    throw new Exception(Messages.BobMergeUnsupported + ": " + widget.description());
            }
            final Node copy = doc.importNode(widget.element(), true);
            final Element previous_element = after == null ? null : widgets.get(after);
            if (previous_element != null  &&  previous_element.getParentNode() == container)
                insertAfter(container, copy, previous_element);
            else
            {
                // First widget in container
                Node first = null;
                for (Node node = container.getFirstChild(); node != null  &&  first == null; node = node.getNextSibling())
                    if (node instanceof Element element  &&  "widget".equals(element.getTagName()))
                        first = node;
                if (first != null)
                {
                    final Node space = first.getPreviousSibling();
                    container.insertBefore(copy, first);
                    if (space instanceof Text text  &&  text.getData().isBlank())
                        container.insertBefore(doc.createTextNode(text.getData()), first);
                }
                else
                {
                    Node last = null;
                    for (Node node = container.getFirstChild(); node != null; node = node.getNextSibling())
                        if (node instanceof Element)
                            last = node;
                    insertAfter(container, copy, last);
                }
            }
        };
    }

    /** @return Index of element among siblings with same tag */
    private static int index(final Element element)
    {
        int index = 0;
        for (Node node = element.getPreviousSibling(); node != null; node = node.getPreviousSibling())
            if (node instanceof Element other  &&  other.getTagName().equals(element.getTagName()))
                ++index;
        return index;
    }

    /** @return n-th child element with tag, or <code>null</code> */
    private static Element child(final Element parent, final String tag, final int n)
    {
        int index = 0;
        for (Node node = parent.getFirstChild(); node != null; node = node.getNextSibling())
            if (node instanceof Element element  &&  element.getTagName().equals(tag))
                if (index++ == n)
                    return element;
        return null;
    }

    /** Insert node after another, with the same indentation
     *  @param parent Parent
     *  @param node Node to insert
     *  @param after Existing child, <code>null</code> to insert as first child
     */
    private static void insertAfter(final Element parent, final Node node, final Node after)
    {
        final Document doc = parent.getOwnerDocument();
        final Node reference = after == null ? parent.getFirstChild() : after.getNextSibling();
        // Indentation of the 'after' node, or of the parent plus 2
        String indent = "\n  ";
        if (after != null  &&  after.getPreviousSibling() instanceof Text text  &&  text.getData().isBlank())
            indent = text.getData();
        else if (parent.getPreviousSibling() instanceof Text text  &&  text.getData().isBlank())
            indent = text.getData() + "  ";
        if (reference == null)
        {
            // Empty element: Add indented node, then indentation of the closing tag
            parent.appendChild(doc.createTextNode(indent));
            parent.appendChild(node);
            parent.appendChild(doc.createTextNode(indent.length() > 2 ? indent.substring(0, indent.length() - 2) : "\n"));
        }
        else
        {
            parent.insertBefore(doc.createTextNode(indent), reference);
            parent.insertBefore(node, reference);
        }
    }

    /** Remove node and the white space before it */
    private static void removeWithSpace(final Node node)
    {
        final Node parent = node.getParentNode();
        final Node space = node.getPreviousSibling();
        if (space instanceof Text text  &&  text.getData().isBlank())
            parent.removeChild(space);
        parent.removeChild(node);
    }
}
