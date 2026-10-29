package org.phoebus.applications.git;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.phoebus.applications.git.BobMerge.Choice;
import org.phoebus.applications.git.BobMerge.Conflict;

/** Tests of merging display files by widget */
@SuppressWarnings("nls")
public class BobMergeTest
{
    private static final String BASE = """
        <?xml version="1.0" encoding="UTF-8"?>
        <display version="2.0.0">
          <name>Test</name>
          <widget type="textupdate" version="2.0.0">
            <name>temp</name>
            <pv_name>SR:T1</pv_name>
            <x>10</x>
          </widget>
          <widget type="label" version="2.0.0">
            <name>title</name>
            <text>Title</text>
          </widget>
          <widget type="group" version="2.0.0">
            <name>Box</name>
            <widget type="led" version="2.0.0">
              <name>led1</name>
              <pv_name>SR:L1</pv_name>
            </widget>
          </widget>
          <widget type="tabs" version="2.0.0">
            <name>Tabs</name>
            <tabs>
              <tab>
                <name>A</name>
                <children>
                  <widget type="led" version="2.0.0">
                    <name>a</name>
                  </widget>
                </children>
              </tab>
              <tab>
                <name>B</name>
                <children>
                </children>
              </tab>
            </tabs>
          </widget>
        </display>
        """;

    private static byte[] bytes(final String text)
    {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    private static String merge(final BobMerge merge) throws Exception
    {
        return new String(merge.getResult(), StandardCharsets.UTF_8);
    }

    @Test
    public void testOneSidedChanges() throws Exception
    {
        final String ours = BASE.replace("<x>10</x>", "<x>20</x>");
        final String theirs = BASE.replace("SR:T1", "SR:T2").replace("<text>Title</text>", "<text>New Title</text>");
        final BobMerge merge = new BobMerge(bytes(BASE), bytes(ours), bytes(theirs));
        assertEquals(0, merge.getConflicts().size());
        assertEquals(2, merge.getAutomatic().size());
        final String result = merge(merge);
        // Everything else, including formatting, as in 'ours'
        assertEquals(ours.replace("SR:T1", "SR:T2").replace("<text>Title</text>", "<text>New Title</text>"), result);
    }

    @Test
    public void testReorderedByOurs() throws Exception
    {
        // Ours moved 'title' before 'temp', theirs changed both: Text merge would conflict, widget merge does not
        final String temp = BASE.substring(BASE.indexOf("  <widget type=\"textupdate\""), BASE.indexOf("  <widget type=\"label\""));
        final String ours = BASE.replace(temp, "").replace("  <widget type=\"group\"", temp + "  <widget type=\"group\"");
        final String theirs = BASE.replace("SR:T1", "SR:T2").replace("<text>Title</text>", "<text>T</text>");
        assertTrue(new MergeModel(bytes(BASE), bytes(ours), bytes(theirs)).getConflicts().size() > 0);
        final BobMerge merge = new BobMerge(bytes(BASE), bytes(ours), bytes(theirs));
        assertEquals(0, merge.getConflicts().size());
        final String result = merge(merge);
        assertEquals(ours.replace("SR:T1", "SR:T2").replace("<text>Title</text>", "<text>T</text>"), result);
    }

    @Test
    public void testPropertyConflict() throws Exception
    {
        final String ours = BASE.replace("SR:T1", "SR:OURS");
        final String theirs = BASE.replace("SR:T1", "SR:THEIRS").replace("<x>10</x>", "<x>30</x>");
        final BobMerge merge = new BobMerge(bytes(BASE), bytes(ours), bytes(theirs));
        final List<Conflict> conflicts = merge.getConflicts();
        assertEquals(1, conflicts.size());
        final Conflict conflict = conflicts.get(0);
        assertEquals("/textupdate:temp", conflict.key);
        assertEquals("pv_name", conflict.property);
        assertEquals("SR:OURS", conflict.ours);
        assertEquals("SR:THEIRS", conflict.theirs);
        assertThrows(Exception.class, () -> merge.getResult());

        conflict.choice = Choice.OURS;
        assertEquals(ours.replace("<x>10</x>", "<x>30</x>"), merge(merge));
        conflict.choice = Choice.THEIRS;
        assertEquals(theirs, merge(merge));
    }

    @Test
    public void testPropertyAddedAndRemoved() throws Exception
    {
        // Theirs adds a property and removes one
        final String theirs = BASE.replace("<x>10</x>", "<x>10</x>\n    <y>5</y>").replace("\n      <pv_name>SR:L1</pv_name>", "");
        final BobMerge merge = new BobMerge(bytes(BASE), bytes(BASE), bytes(theirs));
        assertEquals(0, merge.getConflicts().size());
        assertEquals(theirs, merge(merge));
    }

    @Test
    public void testRemoved() throws Exception
    {
        final String title = BASE.substring(BASE.indexOf("  <widget type=\"label\""), BASE.indexOf("  <widget type=\"group\""));
        // Theirs removed 'title', ours did not change it
        BobMerge merge = new BobMerge(bytes(BASE), bytes(BASE.replace("SR:T1", "X")), bytes(BASE.replace(title, "")));
        assertEquals(0, merge.getConflicts().size());
        assertEquals(BASE.replace("SR:T1", "X").replace(title, ""), merge(merge));

        // Theirs removed 'title', ours changed it: Conflict
        final String ours = BASE.replace("<text>Title</text>", "<text>Changed</text>");
        merge = new BobMerge(bytes(BASE), bytes(ours), bytes(BASE.replace(title, "")));
        assertEquals(1, merge.getConflicts().size());
        assertEquals(null, merge.getConflicts().get(0).property);
        merge.getConflicts().get(0).choice = Choice.OURS;
        assertEquals(ours, merge(merge));
        merge.getConflicts().get(0).choice = Choice.THEIRS;
        assertEquals(BASE.replace(title, ""), merge(merge));

        // Ours removed 'title', theirs changed it: Conflict, 'theirs' restores the widget
        final String theirs = BASE.replace("<text>Title</text>", "<text>Changed</text>");
        merge = new BobMerge(bytes(BASE), bytes(BASE.replace(title, "")), bytes(theirs));
        assertEquals(1, merge.getConflicts().size());
        merge.getConflicts().get(0).choice = Choice.THEIRS;
        assertEquals(theirs, merge(merge));
    }

    @Test
    public void testAdded() throws Exception
    {
        // Theirs adds a widget after 'temp', one inside the group, one in the empty tab
        String theirs = BASE.replace("  <widget type=\"label\"",
                                     "  <widget type=\"label\" version=\"2.0.0\">\n    <name>new</name>\n  </widget>\n" +
                                     "  <widget type=\"label\"");
        theirs = theirs.replace("      <pv_name>SR:L1</pv_name>\n    </widget>\n",
                                "      <pv_name>SR:L1</pv_name>\n    </widget>\n" +
                                "    <widget type=\"led\" version=\"2.0.0\">\n      <name>led2</name>\n    </widget>\n");
        theirs = theirs.replace("        <children>\n        </children>\n",
                                "        <children>\n          <widget type=\"led\" version=\"2.0.0\">\n" +
                                "            <name>b</name>\n          </widget>\n        </children>\n");
        assertTrue(theirs.contains("led2")  &&  theirs.contains("<name>b</name>")  &&  theirs.contains("<name>new</name>"));
        // Ours changes something else
        final String ours = BASE.replace("SR:T1", "X");
        final BobMerge merge = new BobMerge(bytes(BASE), bytes(ours), bytes(theirs));
        assertEquals(0, merge.getConflicts().size());
        assertEquals(3, merge.getAutomatic().size(), merge.getAutomatic().toString());
        final String result = merge(merge);
        assertEquals(theirs.replace("SR:T1", "X"), result);

        // Same widget added on both sides: No conflict
        final BobMerge both = new BobMerge(bytes(BASE), bytes(theirs), bytes(theirs));
        assertEquals(0, both.getConflicts().size());
        assertEquals(theirs, merge(both));
    }

    @Test
    public void testDeletedFile() throws Exception
    {
        assertThrows(Exception.class, () -> new BobMerge(bytes(BASE), new byte[0], bytes(BASE)));
    }
}
