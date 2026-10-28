package org.phoebus.applications.git;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.phoebus.applications.git.SideBySideDiff.Kind;
import org.phoebus.applications.git.SideBySideDiff.Row;

/** Tests of {@link SideBySideDiff} */
@SuppressWarnings("nls")
public class SideBySideDiffTest
{
    /** @return Row as "KIND|left_number:left|right_number:right" */
    private static String describe(final Row row)
    {
        return row.kind() + "|" + row.left_number() + ":" + row.left() + "|" + row.right_number() + ":" + row.right();
    }

    @Test
    public void testPairing()
    {
        final List<String> diff = List.of(
            "diff --git a/x b/x",
            "--- a/x",
            "+++ b/x",
            "@@ -1,4 +1,4 @@",
            " keep",
            "-old a",
            "+new a",
            "+new b",
            " end",
            "-tail");
        final List<String> rows = SideBySideDiff.convert(diff).stream().map(SideBySideDiffTest::describe).toList();
        assertEquals(List.of(
            "HEADER|0:diff --git a/x b/x|0:null",
            "HEADER|0:--- a/x|0:null",
            "HEADER|0:+++ b/x|0:null",
            "HUNK|0:@@ -1,4 +1,4 @@|0:null",
            "CONTEXT|1:keep|1:keep",
            "CHANGE|2:old a|2:new a",
            "CHANGE|0:null|3:new b",
            "CONTEXT|3:end|4:end",
            "CHANGE|4:tail|0:null"),
            rows);
    }

    @Test
    public void testRemovedLineLooksLikeHeader()
    {
        // Removed SQL comment "-- x" is "--- x" in the diff, inside the hunk it's a removed line
        final List<Row> rows = SideBySideDiff.convert(List.of(
            "@@ -1,2 +1,1 @@",
            "--- comment",
            " select 1;"));
        assertEquals(Kind.CHANGE, rows.get(1).kind());
        assertEquals("-- comment", rows.get(1).left());
        assertEquals(Kind.CONTEXT, rows.get(2).kind());
    }

    @Test
    public void testMultipleHunksAndNoNewline()
    {
        final List<Row> rows = SideBySideDiff.convert(List.of(
            "@@ -1 +1 @@",
            "-a",
            "+b",
            "\\ No newline at end of file",
            "@@ -10 +10 @@",
            "-x",
            "+y"));
        assertEquals(List.of("HUNK|0:@@ -1 +1 @@|0:null",
                             "CHANGE|1:a|1:b",
                             "HUNK|0:@@ -10 +10 @@|0:null",
                             "CHANGE|10:x|10:y"),
                     rows.stream().map(SideBySideDiffTest::describe).toList());
    }
}
