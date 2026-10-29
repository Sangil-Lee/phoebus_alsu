package org.phoebus.applications.git;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.StoredConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Tests of staging, unstaging, discarding selected lines */
@SuppressWarnings("nls")
public class LineStageTest
{
    @TempDir
    Path tmp;

    private File dir;
    private Git git;
    private GitRepository repo;

    @BeforeEach
    public void setup() throws Exception
    {
        dir = tmp.resolve("repo").toFile();
        git = Git.init().setDirectory(dir).setInitialBranch("master").call();
        final StoredConfig config = git.getRepository().getConfig();
        config.setString("user", null, "name", "Tester");
        config.setString("user", null, "email", "tester@example.com");
        config.save();
        repo = new GitRepository(dir);
    }

    @AfterEach
    public void cleanup()
    {
        repo.close();
        git.close();
    }

    private void write(final String text) throws Exception
    {
        Files.writeString(dir.toPath().resolve("a.txt"), text);
    }

    private String read() throws Exception
    {
        return Files.readString(dir.toPath().resolve("a.txt"));
    }

    private String index() throws Exception
    {
        return new String(repo.getContent(Revision.INDEX, "a.txt"), StandardCharsets.UTF_8);
    }

    private void commit(final String text) throws Exception
    {
        write(text);
        git.add().addFilepattern("a.txt").call();
        git.commit().setMessage("commit").call();
    }

    /** @param diff Unified diff
     *  @param lines Exact diff lines to select, like "+new"
     *  @return Line indices by hunk, as the diff view provides them
     */
    static Map<Integer, Set<Integer>> select(final String diff, final String... lines)
    {
        final Map<Integer, Set<Integer>> result = new TreeMap<>();
        final String[] shown = diff.split("\\R", -1);
        int hunk = -1, header = -1;
        for (int i=0; i<shown.length; ++i)
        {
            if (shown[i].startsWith("@@"))
            {
                ++hunk;
                header = i;
            }
            else if (hunk >= 0)
                for (String line : lines)
                    if (shown[i].equals(line))
                        result.computeIfAbsent(hunk, h -> new TreeSet<>()).add(i - header - 1);
        }
        return result;
    }

    private static final String BASE = "1\n2\n3\n4\n5\n6\n7\n8\n9\n10\n";

    @Test
    public void testStageSomeLines() throws Exception
    {
        commit(BASE);
        // Replace 3, add line after 5: One hunk
        write("1\n2\nthree\n4\n5\nnew\n6\n7\n8\n9\n10\n");
        String diff = repo.getUnstagedDiff("a.txt");
        assertEquals(1, diff.split("\n@@", -1).length - 1, diff);

        // Only the added line
        repo.stageLines("a.txt", select(diff, "+new"));
        assertEquals("1\n2\n3\n4\n5\nnew\n6\n7\n8\n9\n10\n", index());
        assertEquals("1\n2\nthree\n4\n5\nnew\n6\n7\n8\n9\n10\n", read());

        // Only the removal of '3', not the new 'three'
        diff = repo.getUnstagedDiff("a.txt");
        repo.stageLines("a.txt", select(diff, "-3"));
        assertEquals("1\n2\n4\n5\nnew\n6\n7\n8\n9\n10\n", index());

        // Rest
        diff = repo.getUnstagedDiff("a.txt");
        repo.stageLines("a.txt", select(diff, "+three"));
        assertEquals(read(), index());
    }

    @Test
    public void testSeveralHunks() throws Exception
    {
        final StringBuilder base = new StringBuilder();
        for (int i=1; i<=30; ++i)
            base.append(i).append('\n');
        commit(base.toString());
        write(base.toString().replace("\n2\n", "\ntwo\n").replace("\n28\n", "\ntwenty-eight\n"));
        final String diff = repo.getUnstagedDiff("a.txt");
        final Map<Integer, Set<Integer>> lines = select(diff, "+two", "-28", "+twenty-eight");
        assertEquals(Set.of(0, 1), lines.keySet());
        repo.stageLines("a.txt", lines);
        // Hunk 0: only '+two' without removing '2', hunk 1: all
        assertEquals(base.toString().replace("2\n3\n", "2\ntwo\n3\n").replace("\n28\n", "\ntwenty-eight\n"), index());
    }

    @Test
    public void testUnstageAndDiscard() throws Exception
    {
        commit(BASE);
        write("1\n2\nA\nB\n3\n4\n5\n6\n7\n8\n9\n10\n");
        git.add().addFilepattern("a.txt").call();
        // Unstage only 'B'
        repo.unstageLines("a.txt", select(repo.getStagedDiff("a.txt"), "+B"));
        assertEquals("1\n2\nA\n3\n4\n5\n6\n7\n8\n9\n10\n", index());
        assertEquals("1\n2\nA\nB\n3\n4\n5\n6\n7\n8\n9\n10\n", read());

        // Discard 'B' in work tree, which is the unstaged change
        repo.discardLines("a.txt", select(repo.getUnstagedDiff("a.txt"), "+B"));
        assertEquals("1\n2\nA\n3\n4\n5\n6\n7\n8\n9\n10\n", read());

        // Discard part of a replacement: Restore '5', keep removal of '6'... in the work tree
        write("1\n2\nA\n3\n4\nfive\n7\n8\n9\n10\n");
        final String diff = repo.getUnstagedDiff("a.txt");
        repo.discardLines("a.txt", select(diff, "-5", "+five"));
        assertEquals("1\n2\nA\n3\n4\n5\n7\n8\n9\n10\n", read());
    }

    @Test
    public void testCRLFAndNoNewline() throws Exception
    {
        commit("a\r\nb\r\nc");
        write("a\r\nB\r\nc\r\nd");
        final String diff = repo.getUnstagedDiff("a.txt");
        // Stage replacement of 'b', not the end of file
        repo.stageLines("a.txt", select(diff, "-b\r", "+B\r", "-b", "+B"));
        assertEquals("a\r\nB\r\nc", index());
        // Stage the rest
        repo.stageLines("a.txt", select(repo.getUnstagedDiff("a.txt"), "-c", "+c\r", "+c", "+d"));
        assertEquals("a\r\nB\r\nc\r\nd", index());
    }

    @Test
    public void testOutdated() throws Exception
    {
        commit(BASE);
        write(BASE.replace("3", "three"));
        final String diff = repo.getUnstagedDiff("a.txt");
        final Map<Integer, Set<Integer>> lines = select(diff, "+three");
        // File reverted meanwhile: No hunk to stage
        write(BASE);
        assertThrows(Exception.class, () -> repo.stageLines("a.txt", lines));
        assertEquals(BASE, index());
    }
}
