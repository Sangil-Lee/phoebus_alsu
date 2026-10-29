package org.phoebus.applications.git;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.StoredConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Work tree with CRLF line ends and core.autocrlf=true, like on Windows
 *
 *  <p>The index has LF, the work tree CRLF.
 */
@SuppressWarnings("nls")
public class CrlfTest
{
    @TempDir
    Path tmp;

    private File dir;
    private Git git;
    private GitRepository repo;

    private static final String BASE = "line 1\r\nline 2\r\nline 3\r\nline 4\r\nline 5\r\nline 6\r\nline 7\r\nline 8\r\n";

    @BeforeEach
    public void setup() throws Exception
    {
        dir = tmp.resolve("repo").toFile();
        git = Git.init().setDirectory(dir).setInitialBranch("master").call();
        final StoredConfig config = git.getRepository().getConfig();
        config.setString("user", null, "name", "Tester");
        config.setString("user", null, "email", "tester@example.com");
        config.setString("core", null, "autocrlf", "true");
        config.save();
        write("a.txt", BASE);
        write("old.txt", BASE.replace("line", "old line"));
        git.add().addFilepattern(".").call();
        git.commit().setMessage("base").call();
        repo = new GitRepository(dir);
    }

    @AfterEach
    public void cleanup()
    {
        repo.close();
        git.close();
    }

    private void write(final String path, final String text) throws Exception
    {
        final Path file = dir.toPath().resolve(path);
        Files.createDirectories(file.getParent());
        Files.writeString(file, text);
    }

    private String read(final String path) throws Exception
    {
        return Files.readString(dir.toPath().resolve(path));
    }

    @Test
    public void testIndexHasLF() throws Exception
    {
        assertEquals(BASE.replace("\r\n", "\n"), new String(repo.getContent(Revision.INDEX, "a.txt"), StandardCharsets.UTF_8));
        assertTrue(repo.getStatus().unstaged().isEmpty(), repo.getStatus().toString());
    }

    @Test
    public void testDiscard() throws Exception
    {
        write("a.txt", BASE.replace("line 2\r\n", "line 2\r\nNEW A\r\n").replace("line 6\r\n", "line 6\r\nNEW B\r\n"));
        // Discard only 'NEW B'
        final Map<Integer, Set<Integer>> lines = LineStageTest.select(repo.getUnstagedDiff("a.txt"), "+NEW B");
        repo.discardLines("a.txt", lines);
        assertEquals(BASE.replace("line 2\r\n", "line 2\r\nNEW A\r\n"), read("a.txt"));
        // Discard the remaining hunk
        repo.discardHunk("a.txt", 0);
        assertEquals(BASE, read("a.txt"));
        assertTrue(repo.getStatus().unstaged().isEmpty());
    }

    @Test
    public void testStageLinesKeepsWorkTree() throws Exception
    {
        final String changed = BASE.replace("line 2\r\n", "line 2\r\nNEW A\r\n").replace("line 6\r\n", "line 6\r\nNEW B\r\n");
        write("a.txt", changed);
        repo.stageLines("a.txt", LineStageTest.select(repo.getUnstagedDiff("a.txt"), "+NEW A"));
        assertEquals("line 1\nline 2\nNEW A\nline 3\nline 4\nline 5\nline 6\nline 7\nline 8\n",
                     new String(repo.getContent(Revision.INDEX, "a.txt"), StandardCharsets.UTF_8));
        assertEquals(changed, read("a.txt"));
    }

    @Test
    public void testRestoreWritesCRLF() throws Exception
    {
        write("a.txt", "changed\r\n");
        repo.restore(repo.resolve("HEAD"), "a.txt", "a.txt", false);
        assertEquals(BASE, read("a.txt"));
        assertTrue(repo.getStatus().unstaged().isEmpty(), repo.getStatus().toString());
        // From index
        write("a.txt", "changed\r\n");
        repo.restore(Revision.INDEX, "a.txt", "a.txt", false);
        assertEquals(BASE, read("a.txt"));
        // Older version of renamed file
        repo.restore(repo.resolve("HEAD"), "old.txt", "renamed.txt", false);
        assertEquals(BASE.replace("line", "old line"), read("renamed.txt"));
    }

    @Test
    public void testWriteWorkTree() throws Exception
    {
        // Merge tool result, from index stages with LF
        repo.writeWorkTree("merged.txt", "a\nb\n".getBytes(StandardCharsets.UTF_8));
        assertEquals("a\r\nb\r\n", read("merged.txt"));
        // Binary content is not converted
        final byte[] binary = { 'a', 0, '\n', 'b' };
        repo.writeWorkTree("data.bin", binary);
        assertArrayEquals(binary, Files.readAllBytes(dir.toPath().resolve("data.bin")));
        // .gitattributes can request LF
        write(".gitattributes", "*.bob text eol=lf\r\n");
        repo.writeWorkTree("ui/x.bob", "<display/>\n".getBytes(StandardCharsets.UTF_8));
        assertEquals("<display/>\n", read("ui/x.bob"));
    }

    @Test
    public void testCompareFindsRename() throws Exception
    {
        Files.move(dir.toPath().resolve("old.txt"), dir.toPath().resolve("new.txt"));
        git.add().addFilepattern("new.txt").call();
        git.rm().addFilepattern("old.txt").call();
        final List<String> files = repo.compare(repo.resolve("HEAD"), Revision.WORK_TREE, null)
                                       .stream().map(f -> f.type().name() + " " + f.path()).toList();
        assertEquals(List.of("RENAMED new.txt"), files);
        // Unchanged file is not listed as modified
        assertFalse(files.contains("MODIFIED a.txt"));
    }

    @Test
    public void testApplyLFPatch() throws Exception
    {
        final String patch = """
            diff --git a/a.txt b/a.txt
            index 0000000..1111111 100644
            --- a/a.txt
            +++ b/a.txt
            @@ -3,3 +3,4 @@ line 2
             line 3
             line 4
            +patched
             line 5
            """;
        repo.applyPatch(patch.getBytes(StandardCharsets.UTF_8));
        assertEquals(BASE.replace("line 4\r\n", "line 4\r\npatched\r\n"), read("a.txt"));
    }

    @Test
    public void testBobMergeKeepsLineEnds() throws Exception
    {
        final String ours = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<display version=\"2.0.0\">\n  <name>A</name>\n  <width>10</width>\n</display>\n";
        final String theirs = ours.replace("<width>10</width>", "<width>20</width>");
        final String original = System.getProperty("line.separator");
        // XML output on Windows uses CRLF
        System.setProperty("line.separator", "\r\n");
        try
        {
            final BobMerge merge = new BobMerge(ours.getBytes(StandardCharsets.UTF_8), ours.getBytes(StandardCharsets.UTF_8),
                                                theirs.getBytes(StandardCharsets.UTF_8));
            assertEquals(theirs, new String(merge.getResult(), StandardCharsets.UTF_8));
            // CRLF file stays CRLF
            final BobMerge crlf = new BobMerge(ours.replace("\n", "\r\n").getBytes(StandardCharsets.UTF_8),
                                               ours.replace("\n", "\r\n").getBytes(StandardCharsets.UTF_8),
                                               theirs.replace("\n", "\r\n").getBytes(StandardCharsets.UTF_8));
            assertEquals(theirs.replace("\n", "\r\n"), new String(crlf.getResult(), StandardCharsets.UTF_8));
        }
        finally
        {
            System.setProperty("line.separator", original);
        }
    }
}
