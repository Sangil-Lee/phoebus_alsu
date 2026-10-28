package org.phoebus.applications.git;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Converts a unified diff into rows for a side-by-side view */
@SuppressWarnings("nls")
class SideBySideDiff
{
    /** Kind of row */
    enum Kind
    {
        /** 'diff --git', 'index', commit message, ... spanning both sides */
        HEADER,
        /** '@@ -1,2 +1,3 @@' */
        HUNK,
        /** Unchanged line on both sides */
        CONTEXT,
        /** Removed line on the left and/or added line on the right */
        CHANGE
    }

    /** One row
     *  @param left Text on the left (old) side, <code>null</code> if there is no line
     *  @param left_number Line number on the left side, 0 if none
     *  @param right Text on the right (new) side, <code>null</code> if there is no line
     *  @param right_number Line number on the right side, 0 if none
     */
    record Row(Kind kind, String left, int left_number, String right, int right_number) {}

    private static final Pattern HUNK = Pattern.compile("^@@ -(\\d+)(?:,(\\d+))? \\+(\\d+)(?:,(\\d+))? @@.*");

    /** @param lines Lines of a unified diff
     *  @return Side-by-side rows
     */
    static List<Row> convert(final List<String> lines)
    {
        final List<Row> rows = new ArrayList<>();
        final List<String> removed = new ArrayList<>(), added = new ArrayList<>();
        int old_line = 0, new_line = 0;
        // Lines remaining in the current hunk, used to tell hunk lines from headers.
        // A removed line "-- x" would otherwise look like a "--- a/file" header
        int old_remaining = 0, new_remaining = 0;
        for (String line : lines)
        {
            if (old_remaining > 0  ||  new_remaining > 0)
            {
                if (line.startsWith("-"))
                {
                    removed.add(line.substring(1));
                    --old_remaining;
                    continue;
                }
                if (line.startsWith("+"))
                {
                    added.add(line.substring(1));
                    --new_remaining;
                    continue;
                }
                if (line.startsWith(" ")  ||  line.isEmpty())
                {
                    final int[] numbers = flush(rows, removed, added, old_line, new_line);
                    old_line = numbers[0];
                    new_line = numbers[1];
                    final String text = line.isEmpty() ? "" : line.substring(1);
                    rows.add(new Row(Kind.CONTEXT, text, old_line++, text, new_line++));
                    --old_remaining;
                    --new_remaining;
                    continue;
                }
            }
            final int[] numbers = flush(rows, removed, added, old_line, new_line);
            old_line = numbers[0];
            new_line = numbers[1];
            if (line.startsWith("\\"))
                continue; // "\ No newline at end of file"
            final Matcher hunk = HUNK.matcher(line);
            if (hunk.matches())
            {
                old_line = Integer.parseInt(hunk.group(1));
                old_remaining = hunk.group(2) == null ? 1 : Integer.parseInt(hunk.group(2));
                new_line = Integer.parseInt(hunk.group(3));
                new_remaining = hunk.group(4) == null ? 1 : Integer.parseInt(hunk.group(4));
                rows.add(new Row(Kind.HUNK, line, 0, null, 0));
            }
            else
                rows.add(new Row(Kind.HEADER, line, 0, null, 0));
        }
        flush(rows, removed, added, old_line, new_line);
        return rows;
    }

    /** Pair removed and added lines into rows
     *  @return Updated old and new line numbers
     */
    private static int[] flush(final List<Row> rows, final List<String> removed, final List<String> added,
                               int old_line, int new_line)
    {
        final int count = Math.max(removed.size(), added.size());
        for (int i=0; i<count; ++i)
        {
            final String left = i < removed.size() ? removed.get(i) : null;
            final String right = i < added.size() ? added.get(i) : null;
            rows.add(new Row(Kind.CHANGE,
                             left, left == null ? 0 : old_line++,
                             right, right == null ? 0 : new_line++));
        }
        removed.clear();
        added.clear();
        return new int[] { old_line, new_line };
    }
}
