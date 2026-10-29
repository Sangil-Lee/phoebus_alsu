package org.phoebus.applications.git;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.eclipse.jgit.diff.DiffAlgorithm;
import org.eclipse.jgit.diff.DiffAlgorithm.SupportedAlgorithm;
import org.eclipse.jgit.diff.RawText;
import org.eclipse.jgit.diff.RawTextComparator;
import org.eclipse.jgit.merge.MergeAlgorithm;
import org.eclipse.jgit.merge.MergeChunk;
import org.eclipse.jgit.merge.MergeResult;

/** Three-way merge of text, line by line, with choices for each conflict
 *
 *  <p>Lines are kept as the original bytes, including their line end,
 *  so line ends and encoding are preserved.
 */
@SuppressWarnings("nls")
class MergeModel
{
    /** How a conflict is resolved */
    enum Choice
    {
        UNRESOLVED, OURS, THEIRS, OURS_THEN_THEIRS, THEIRS_THEN_OURS, EDITED
    }

    /** Part of the merged file: Lines that merged without conflict, or a conflict */
    static class Chunk
    {
        /** Is this a conflict? */
        final boolean conflict;

        /** Lines of base, ours, theirs. Without conflict, only 'ours' is used */
        final List<byte[]> base, ours, theirs;

        /** First line of the conflict in base, ours, theirs (0-based) */
        final int base_start, ours_start, theirs_start;

        /** Choice for a conflict */
        Choice choice = Choice.UNRESOLVED;

        /** Lines when edited */
        List<byte[]> edited = List.of();

        Chunk(final List<byte[]> lines)
        {
            conflict = false;
            base = theirs = List.of();
            ours = lines;
            base_start = ours_start = theirs_start = -1;
        }

        Chunk(final List<byte[]> base, final int base_start,
              final List<byte[]> ours, final int ours_start,
              final List<byte[]> theirs, final int theirs_start)
        {
            conflict = true;
            this.base = base;
            this.ours = ours;
            this.theirs = theirs;
            this.base_start = base_start;
            this.ours_start = ours_start;
            this.theirs_start = theirs_start;
        }

        /** @return Is the chunk resolved, i.e. no conflict or a choice was made? */
        boolean isResolved()
        {
            return ! conflict  ||  choice != Choice.UNRESOLVED;
        }

        /** @return Lines of the result, <code>null</code> for an unresolved conflict */
        List<byte[]> getResult()
        {
            if (! conflict)
                return ours;
            final List<byte[]> result = new ArrayList<>();
            switch (choice)
            {
            case OURS:
                result.addAll(ours);
                break;
            case THEIRS:
                result.addAll(theirs);
                break;
            case OURS_THEN_THEIRS:
                result.addAll(ours);
                result.addAll(theirs);
                break;
            case THEIRS_THEN_OURS:
                result.addAll(theirs);
                result.addAll(ours);
                break;
            case EDITED:
                result.addAll(edited);
                break;
            default:
                return null;
            }
            return result;
        }
    }

    /** Marker lines for unresolved conflicts, like git */
    static final String MARK_OURS = "<<<<<<< ours", MARK_BASE = "||||||| base", MARK_SEP = "=======", MARK_THEIRS = ">>>>>>> theirs";

    private final List<byte[]> base_lines, ours_lines, theirs_lines;
    private final List<Chunk> chunks = new ArrayList<>();
    private final List<Chunk> conflicts = new ArrayList<>();
    private final String newline;

    /** @param base Content of common ancestor, empty if none
     *  @param ours Our content
     *  @param theirs Their content
     */
    MergeModel(final byte[] base, final byte[] ours, final byte[] theirs)
    {
        base_lines = split(base);
        ours_lines = split(ours);
        theirs_lines = split(theirs);
        newline = new String(ours, StandardCharsets.ISO_8859_1).contains("\r\n") ? "\r\n" : "\n";

        final MergeAlgorithm algorithm = new MergeAlgorithm(DiffAlgorithm.getAlgorithm(SupportedAlgorithm.HISTOGRAM));
        final MergeResult<RawText> result = algorithm.merge(RawTextComparator.DEFAULT,
                                                            new RawText(base), new RawText(ours), new RawText(theirs));
        final List<List<byte[]>> sequences = List.of(base_lines, ours_lines, theirs_lines);
        List<byte[]> conflict_base = List.of(), conflict_ours = List.of();
        int base_start = 0, ours_start = 0;
        for (MergeChunk chunk : result)
        {
            // Range can exceed an empty base
            final List<byte[]> sequence = sequences.get(chunk.getSequenceIndex());
            final int begin = Math.min(chunk.getBegin(), sequence.size());
            final List<byte[]> lines = sequence.subList(begin, Math.max(begin, Math.min(chunk.getEnd(), sequence.size())));
            switch (chunk.getConflictState())
            {
            case FIRST_CONFLICTING_RANGE:
                conflict_ours = lines;
                ours_start = begin;
                conflict_base = List.of();
                base_start = -1;
                break;
            case BASE_CONFLICTING_RANGE:
                conflict_base = lines;
                base_start = begin;
                break;
            case NEXT_CONFLICTING_RANGE:
                final Chunk conflict = new Chunk(conflict_base, base_start, conflict_ours, ours_start, lines, begin);
                chunks.add(conflict);
                conflicts.add(conflict);
                break;
            default:
                // Merge adjacent chunks without conflict
                if (! chunks.isEmpty()  &&  ! chunks.get(chunks.size()-1).conflict)
                {
                    final List<byte[]> joined = new ArrayList<>(chunks.get(chunks.size()-1).ours);
                    joined.addAll(lines);
                    chunks.set(chunks.size()-1, new Chunk(joined));
                }
                else
                    chunks.add(new Chunk(lines));
            }
        }
    }

    /** @param content File content
     *  @return Lines, each with its line end
     */
    static List<byte[]> split(final byte[] content)
    {
        final List<byte[]> lines = new ArrayList<>();
        int start = 0;
        for (int i=0; i<content.length; ++i)
            if (content[i] == '\n')
            {
                lines.add(Arrays.copyOfRange(content, start, i+1));
                start = i+1;
            }
        if (start < content.length)
            lines.add(Arrays.copyOfRange(content, start, content.length));
        return lines;
    }

    /** @param content Content
     *  @return Is it binary, i.e. not suitable for a line merge?
     */
    static boolean isBinary(final byte[] content)
    {
        return RawText.isBinary(content);
    }

    /** @return All lines of base, ours, theirs */
    List<byte[]> getBase()
    {
        return base_lines;
    }

    List<byte[]> getOurs()
    {
        return ours_lines;
    }

    List<byte[]> getTheirs()
    {
        return theirs_lines;
    }

    /** @return Line end used by 'ours' */
    String getNewline()
    {
        return newline;
    }

    /** @return Chunks of the merged file */
    List<Chunk> getChunks()
    {
        return chunks;
    }

    /** @return Conflicts */
    List<Chunk> getConflicts()
    {
        return conflicts;
    }

    /** @return Number of unresolved conflicts */
    int getUnresolved()
    {
        return (int) conflicts.stream().filter(c -> ! c.isResolved()).count();
    }

    /** @param conflict Conflict
     *  @param text Edited text for the conflict
     */
    void setEdited(final Chunk conflict, final String text)
    {
        String value = text;
        // Text area uses '\n', restore the file's line end
        if (! "\n".equals(newline))
            value = value.replace("\r\n", "\n").replace("\n", newline);
        conflict.edited = split(value.getBytes(StandardCharsets.UTF_8));
        conflict.choice = Choice.EDITED;
    }

    /** @param chunk Chunk
     *  @return Lines of the chunk in the result, with markers for an unresolved conflict
     */
    List<byte[]> getResultLines(final Chunk chunk)
    {
        final List<byte[]> result = chunk.getResult();
        if (result != null)
            return result;
        final List<byte[]> marked = new ArrayList<>();
        marked.add(line(MARK_OURS));
        marked.addAll(terminated(chunk.ours));
        if (chunk.base_start >= 0)
        {
            marked.add(line(MARK_BASE));
            marked.addAll(terminated(chunk.base));
        }
        marked.add(line(MARK_SEP));
        marked.addAll(terminated(chunk.theirs));
        marked.add(line(MARK_THEIRS));
        return marked;
    }

    private byte[] line(final String text)
    {
        return (text + newline).getBytes(StandardCharsets.UTF_8);
    }

    /** @param lines Lines
     *  @return Lines where the last line has a line end
     */
    private List<byte[]> terminated(final List<byte[]> lines)
    {
        if (lines.isEmpty()  ||  endsWithNewline(lines.get(lines.size()-1)))
            return lines;
        final List<byte[]> result = new ArrayList<>(lines);
        result.set(result.size()-1, withNewline(result.get(result.size()-1)));
        return result;
    }

    private static boolean endsWithNewline(final byte[] line)
    {
        return line.length > 0  &&  line[line.length-1] == '\n';
    }

    private byte[] withNewline(final byte[] line)
    {
        final byte[] end = newline.getBytes(StandardCharsets.ISO_8859_1);
        final byte[] result = Arrays.copyOf(line, line.length + end.length);
        System.arraycopy(end, 0, result, line.length, end.length);
        return result;
    }

    /** @return Merged content. Unresolved conflicts are written with markers */
    byte[] getResult()
    {
        final List<byte[]> lines = new ArrayList<>();
        for (Chunk chunk : chunks)
            lines.addAll(getResultLines(chunk));
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (int i=0; i<lines.size(); ++i)
        {
            // A line without line end (end of one side) that's followed by more lines needs one
            final byte[] line = i < lines.size()-1  &&  ! endsWithNewline(lines.get(i))
                              ? withNewline(lines.get(i))
                              : lines.get(i);
            out.writeBytes(line);
        }
        return out.toByteArray();
    }

    /** @param line Line
     *  @return Text of the line without line end
     */
    static String toText(final byte[] line)
    {
        int end = line.length;
        while (end > 0  &&  (line[end-1] == '\n'  ||  line[end-1] == '\r'))
            --end;
        return new String(line, 0, end, StandardCharsets.UTF_8);
    }
}
