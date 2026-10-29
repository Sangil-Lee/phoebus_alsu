package org.phoebus.applications.git;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.phoebus.applications.git.BobDiff.Change;
import org.phoebus.applications.git.BobDiff.Kind;

/** Tests of {@link BobDiff} */
@SuppressWarnings("nls")
public class BobDiffTest
{
    private static byte[] bob(final String body)
    {
        return ("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<display version=\"2.0.0\">\n" + body + "</display>\n")
               .getBytes(StandardCharsets.UTF_8);
    }

    private static final String TEMP =
        "  <widget type=\"textupdate\" version=\"2.0.0\">\n" +
        "    <name>temp</name>\n" +
        "    <pv_name>SR:T1</pv_name>\n" +
        "    <x>10</x>\n" +
        "  </widget>\n";

    private static List<String> describe(final List<Change> changes)
    {
        return changes.stream()
                      .map(c -> c.kind() + "|" + c.widget() + "|" + c.property() + "|" + c.old_value() + "|" + c.new_value())
                      .toList();
    }

    @Test
    public void testPropertyChange() throws Exception
    {
        final List<Change> changes = BobDiff.compare(bob("  <name>Main</name>\n" + TEMP),
                                                     bob("  <name>Main</name>\n" + TEMP.replace("SR:T1", "SR:T2").replace("<x>10</x>", "")));
        assertEquals(List.of("CHANGED|textupdate 'temp'|pv_name|SR:T1|SR:T2",
                             "CHANGED|textupdate 'temp'|x|10|null"),
                     describe(changes));
    }

    @Test
    public void testAddRemoveAndDisplay() throws Exception
    {
        final String label = "  <widget type=\"label\" version=\"2.0.0\">\n    <name>title</name>\n    <text>Hello</text>\n  </widget>\n";
        final List<Change> changes = BobDiff.compare(bob("  <name>Old</name>\n" + TEMP),
                                                     bob("  <name>New</name>\n  <width>800</width>\n" + label));
        assertEquals(List.of("CHANGED|" + Messages.DisplayProperties + "|name|Old|New",
                             "CHANGED|" + Messages.DisplayProperties + "|width|null|800",
                             "ADDED|label 'title'|null|null|null",
                             "REMOVED|textupdate 'temp'|null|null|null"),
                     describe(changes));
    }

    @Test
    public void testNestedAndStructured() throws Exception
    {
        final String group =
            "  <widget type=\"group\" version=\"2.0.0\">\n" +
            "    <name>g1</name>\n" +
            "    <widget type=\"action_button\" version=\"3.0.0\">\n" +
            "      <name>open</name>\n" +
            "      <actions>\n" +
            "        <action type=\"open_display\">\n" +
            "          <file>detail.bob</file>\n" +
            "        </action>\n" +
            "      </actions>\n" +
            "    </widget>\n" +
            "  </widget>\n";
        final List<Change> changes = BobDiff.compare(bob(group), bob(group.replace("detail.bob", "other.bob")));
        assertEquals(1, changes.size());
        final Change change = changes.get(0);
        assertEquals("action_button 'open'  (group 'g1')", change.widget());
        assertEquals("actions", change.property());
        assertTrue(change.old_value().contains("detail.bob"));
        assertTrue(change.new_value().contains("other.bob"));
        // Group itself is unchanged: Its nested widgets are not one of its properties
        assertTrue(changes.stream().noneMatch(c -> c.widget().startsWith("group")));
    }

    @Test
    public void testSameNameAndFormatting() throws Exception
    {
        // Two widgets with the same type and name are told apart by order
        final String twice = TEMP + TEMP.replace("SR:T1", "SR:T9");
        final List<Change> changes = BobDiff.compare(bob(twice), bob(twice.replace("SR:T9", "SR:T8")));
        assertEquals(List.of("CHANGED|textupdate 'temp' #2|pv_name|SR:T9|SR:T8"), describe(changes));

        // Only indentation changed
        assertEquals(List.of(), BobDiff.compare(bob(TEMP), bob(TEMP.replace("    ", "\t"))));
        assertEquals(List.of(Messages.NoWidgetChanges), BobDiff.format(bob(TEMP), bob(TEMP.replace("    ", "\t"))));
    }

    @Test
    public void testNewFileAndError() throws Exception
    {
        // New file: Display and its widgets are added
        assertEquals(List.of("ADDED|" + Messages.DisplayProperties + "|null|null|null", "ADDED|textupdate 'temp'|null|null|null"),
                     describe(BobDiff.compare(new byte[0], bob("  <name>Main</name>\n" + TEMP))));
        final byte[] conflict = "<<<<<<< HEAD\n<display>".getBytes(StandardCharsets.UTF_8);
        assertThrows(Exception.class, () -> BobDiff.compare(conflict, bob(TEMP)));
        assertTrue(BobDiff.format(conflict, bob(TEMP)).get(0).startsWith("! " + Messages.CannotParse));
        // No external entities
        final byte[] entity = ("<?xml version=\"1.0\"?><!DOCTYPE d [<!ENTITY x SYSTEM \"file:///etc/passwd\">]><display><name>&x;</name></display>")
                              .getBytes(StandardCharsets.UTF_8);
        assertThrows(Exception.class, () -> BobDiff.compare(entity, entity));
    }
}
