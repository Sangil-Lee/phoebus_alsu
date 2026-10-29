package org.phoebus.applications.git;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;
import org.phoebus.applications.git.MergeModel.Chunk;
import org.phoebus.applications.git.MergeModel.Choice;

/** Tests of the three-way merge model */
@SuppressWarnings("nls")
public class MergeModelTest
{
    private static byte[] bytes(final String text)
    {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    private static String result(final MergeModel model)
    {
        return new String(model.getResult(), StandardCharsets.UTF_8);
    }

    @Test
    public void testNoConflict()
    {
        final MergeModel model = new MergeModel(bytes("a\nb\nc\n"), bytes("A\nb\nc\n"), bytes("a\nb\nC\n"));
        assertEquals(0, model.getConflicts().size());
        assertEquals("A\nb\nC\n", result(model));
    }

    @Test
    public void testChoices()
    {
        final String base = "1\n2\nx\n4\n5\ny\n7\n";
        final String ours = "1\n2\nOURS-X\n4\n5\nOURS-Y\n7\n";
        final String theirs = "1\n2\nTHEIRS-X\n4\n5\nTHEIRS-Y\n7\n";
        final MergeModel model = new MergeModel(bytes(base), bytes(ours), bytes(theirs));
        assertEquals(2, model.getConflicts().size());
        assertEquals(2, model.getUnresolved());

        final Chunk first = model.getConflicts().get(0);
        assertEquals(2, first.ours_start);
        assertEquals(2, first.theirs_start);
        assertEquals(2, first.base_start);
        assertEquals("x", MergeModel.toText(first.base.get(0)));

        // Unresolved: Markers like git, with base
        String text = result(model);
        assertTrue(text.contains("<<<<<<< ours\nOURS-X\n||||||| base\nx\n=======\nTHEIRS-X\n>>>>>>> theirs\n"), text);

        first.choice = Choice.OURS;
        model.getConflicts().get(1).choice = Choice.THEIRS;
        assertEquals(0, model.getUnresolved());
        assertEquals("1\n2\nOURS-X\n4\n5\nTHEIRS-Y\n7\n", result(model));

        first.choice = Choice.OURS_THEN_THEIRS;
        assertEquals("1\n2\nOURS-X\nTHEIRS-X\n4\n5\nTHEIRS-Y\n7\n", result(model));
        first.choice = Choice.THEIRS_THEN_OURS;
        assertEquals("1\n2\nTHEIRS-X\nOURS-X\n4\n5\nTHEIRS-Y\n7\n", result(model));

        model.setEdited(first, "custom 1\ncustom 2\n");
        assertEquals(Choice.EDITED, first.choice);
        assertEquals("1\n2\ncustom 1\ncustom 2\n4\n5\nTHEIRS-Y\n7\n", result(model));
        // Edited text without final newline still separates lines
        model.setEdited(first, "custom");
        assertEquals("1\n2\ncustom\n4\n5\nTHEIRS-Y\n7\n", result(model));
        // Edited to nothing removes the lines
        model.setEdited(first, "");
        assertEquals("1\n2\n4\n5\nTHEIRS-Y\n7\n", result(model));
    }

    @Test
    public void testCRLF()
    {
        final MergeModel model = new MergeModel(bytes("a\r\nb\r\n"), bytes("a\r\nOURS\r\n"), bytes("a\r\nTHEIRS\r\n"));
        assertEquals("\r\n", model.getNewline());
        assertEquals(1, model.getConflicts().size());
        assertTrue(result(model).contains("<<<<<<< ours\r\nOURS\r\n"));
        model.getConflicts().get(0).choice = Choice.THEIRS;
        assertEquals("a\r\nTHEIRS\r\n", result(model));
        // Edited text uses '\n', file keeps CRLF
        model.setEdited(model.getConflicts().get(0), "X\nY\n");
        assertEquals("a\r\nX\r\nY\r\n", result(model));
    }

    @Test
    public void testNoFinalNewline()
    {
        final MergeModel model = new MergeModel(bytes("a\nb"), bytes("a\nours"), bytes("a\ntheirs"));
        assertEquals(1, model.getConflicts().size());
        model.getConflicts().get(0).choice = Choice.OURS;
        assertEquals("a\nours", result(model));
        // Joining both must not glue the lines
        model.getConflicts().get(0).choice = Choice.OURS_THEN_THEIRS;
        assertEquals("a\nours\ntheirs", result(model));
        model.getConflicts().get(0).choice = Choice.UNRESOLVED;
        assertTrue(result(model).contains("ours\n||||||| base\nb\n=======\ntheirs\n>>>>>>> theirs\n"), result(model));
    }

    @Test
    public void testBothAdded()
    {
        // No base, e.g. file added on both branches
        final MergeModel model = new MergeModel(new byte[0], bytes("same\nours\n"), bytes("same\ntheirs\n"));
        assertEquals(1, model.getConflicts().size());
        model.getConflicts().get(0).choice = Choice.THEIRS;
        assertEquals("same\ntheirs\n", result(model));
    }

    @Test
    public void testBinary()
    {
        assertTrue(MergeModel.isBinary(new byte[] { 'a', 0, 'b' }));
        assertFalse(MergeModel.isBinary(bytes("text\n")));
    }
}
