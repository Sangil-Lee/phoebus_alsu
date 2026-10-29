package org.phoebus.applications.git;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.MergeResult;
import org.eclipse.jgit.lib.StoredConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.phoebus.applications.git.GitRepository.ChangeType;
import org.phoebus.applications.git.GitRepository.CommitFile;

/** Tests of comparing, restoring, exporting revisions */
@SuppressWarnings("nls")
public class RevisionTest
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

    private String commit(final String message) throws Exception
    {
        git.add().addFilepattern(".").call();
        git.add().addFilepattern(".").setUpdate(true).call();
        return git.commit().setMessage(message).call().name();
    }

    private static String text(final byte[] content)
    {
        return new String(content, StandardCharsets.UTF_8);
    }

    @Test
    public void testResolve() throws Exception
    {
        write("a.txt", "one\n");
        final String first = commit("first");
        git.tag().setName("v1").call();
        write("a.txt", "two\n");
        final String second = commit("second");

        assertEquals(second, repo.resolve("master").id());
        assertEquals("master", repo.resolve("master").label());
        assertEquals(first, repo.resolve("v1").id());
        assertEquals(first, repo.resolve("HEAD~1").id());
        assertEquals(first.substring(0, 7), repo.resolve(first).label());
        assertEquals(first, repo.resolve(first.substring(0, 10)).id());
        assertThrows(Exception.class, () -> repo.resolve("nonexisting"));
    }

    @Test
    public void testCompareCommitsIndexWorkTree() throws Exception
    {
        write("a.txt", "one\n");
        write("b.txt", "bee\n");
        write("dir/c.txt", "see\n");
        final Revision.Commit first = repo.resolve(commit("first"));
        write("a.txt", "two\n");
        Files.delete(dir.toPath().resolve("b.txt"));
        write("d.txt", "new\n");
        final Revision.Commit second = repo.resolve(commit("second"));

        // Commits
        List<CommitFile> files = repo.compare(first, second, null);
        assertEquals(List.of(new CommitFile(ChangeType.MODIFIED, "a.txt", null),
                             new CommitFile(ChangeType.DELETED, "b.txt", null),
                             new CommitFile(ChangeType.ADDED, "d.txt", null)), files);
        // Reverse
        files = repo.compare(second, first, null);
        assertEquals(ChangeType.ADDED, files.get(1).type());
        // Limited to paths
        assertEquals(1, repo.compare(first, second, List.of("a.txt")).size());

        // Commit vs. work tree: Staged and unstaged edits, untracked and ignored files are not listed
        write("a.txt", "three\n");
        write("dir/c.txt", "sea\n");
        git.add().addFilepattern("dir/c.txt").call();
        write("untracked.txt", "x\n");
        write(".gitignore", "*.log\n");
        write("ignored.log", "x\n");
        files = repo.compare(second, Revision.WORK_TREE, null);
        assertEquals(List.of(new CommitFile(ChangeType.MODIFIED, "a.txt", null),
                             new CommitFile(ChangeType.MODIFIED, "dir/c.txt", null)), files);
        // Index vs. work tree: Only unstaged
        files = repo.compare(Revision.INDEX, Revision.WORK_TREE, null);
        assertEquals(List.of(new CommitFile(ChangeType.MODIFIED, "a.txt", null)), files);
        // Commit vs. index: Only staged
        files = repo.compare(second, Revision.INDEX, null);
        assertEquals(List.of(new CommitFile(ChangeType.MODIFIED, "dir/c.txt", null)), files);
        // Work tree as old side
        files = repo.compare(Revision.WORK_TREE, first, null);
        // a, c modified, b only in commit, d only in work tree; untracked .gitignore not listed
        assertEquals(4, files.size());

        // Diff text
        final String diff = repo.getDiff(first, Revision.WORK_TREE, List.of("a.txt"));
        assertTrue(diff.contains("-one"), diff);
        assertTrue(diff.contains("+three"), diff);
        assertFalse(diff.contains("c.txt"), diff);
        final String all = repo.getDiff(second, Revision.WORK_TREE, null);
        assertTrue(all.contains("+sea"), all);
        assertFalse(all.contains("untracked"), all);

        // Content
        assertEquals("one\n", text(repo.getContent(first, "a.txt")));
        assertEquals("three\n", text(repo.getContent(Revision.WORK_TREE, "a.txt")));
        assertEquals("two\n", text(repo.getContent(Revision.INDEX, "a.txt")));
        assertEquals("", text(repo.getContent(second, "b.txt")));
        assertFalse(repo.exists(second, "b.txt"));
        assertTrue(repo.exists(first, "b.txt"));
        assertFalse(repo.exists(first, "dir"));
    }

    @Test
    public void testRename() throws Exception
    {
        final String text = "line 1\nline 2\nline 3\nline 4\nline 5\n";
        write("old.txt", text);
        final Revision.Commit first = repo.resolve(commit("first"));
        Files.move(dir.toPath().resolve("old.txt"), dir.toPath().resolve("new.txt"));
        write("new.txt", text + "line 6\n");
        final Revision.Commit second = repo.resolve(commit("moved"));

        List<CommitFile> files = repo.compare(first, second, null);
        assertEquals(List.of(new CommitFile(ChangeType.RENAMED, "new.txt", "old.txt")), files);
        assertEquals("line 6\n", text(repo.getVersions(first, second, files.get(0)).new_content()).substring(35));
        assertEquals(text, text(repo.getVersions(first, second, files.get(0)).old_content()));

        // Rename in work tree, not staged: Old file deleted, new one untracked
        Files.move(dir.toPath().resolve("new.txt"), dir.toPath().resolve("newer.txt"));
        files = repo.compare(second, Revision.WORK_TREE, null);
        assertEquals(List.of(new CommitFile(ChangeType.DELETED, "new.txt", null)), files);
        // .. once staged, it's a rename
        git.add().addFilepattern("newer.txt").call();
        git.rm().addFilepattern("new.txt").call();
        files = repo.compare(second, Revision.WORK_TREE, null);
        assertEquals(List.of(new CommitFile(ChangeType.RENAMED, "newer.txt", "new.txt")), files);
        final String diff = repo.getDiff(second, Revision.WORK_TREE, List.of("newer.txt", "new.txt"));
        assertTrue(diff.contains("rename from new.txt"), diff);
    }

    @Test
    public void testRestore() throws Exception
    {
        write("a.txt", "one\n");
        write("b.txt", "bee\n");
        final Revision.Commit first = repo.resolve(commit("first"));
        write("a.txt", "two\n");
        write("c.txt", "see\n");
        commit("second");

        // Work tree only: Index keeps 'two'
        repo.restore(first, "a.txt", "a.txt", false);
        assertEquals("one\n", read("a.txt"));
        assertEquals("two\n", text(repo.getContent(Revision.INDEX, "a.txt")));
        assertEquals(List.of("a.txt"), repo.getStatus().unstaged().stream().map(e -> e.path()).toList());

        // Work tree and index
        write("a.txt", "edit\n");
        repo.restore(first, "a.txt", "a.txt", true);
        assertEquals("one\n", read("a.txt"));
        assertEquals("one\n", text(repo.getContent(Revision.INDEX, "a.txt")));
        assertTrue(repo.getStatus().unstaged().isEmpty());
        assertEquals(List.of("a.txt"), repo.getStatus().staged().stream().map(e -> e.path()).toList());

        // File that's not in the old version is deleted
        repo.restore(first, "c.txt", "c.txt", false);
        assertFalse(new File(dir, "c.txt").exists());
        assertTrue(repo.exists(Revision.INDEX, "c.txt"));
        write("c.txt", "see\n");
        repo.restore(first, "c.txt", "c.txt", true);
        assertFalse(new File(dir, "c.txt").exists());
        assertFalse(repo.exists(Revision.INDEX, "c.txt"));

        // Restore from index
        write("b.txt", "changed\n");
        repo.restore(Revision.INDEX, "b.txt", "b.txt", false);
        assertEquals("bee\n", read("b.txt"));

        // Old version of a renamed file
        repo.restore(first, "b.txt", "renamed/b2.txt", true);
        assertEquals("bee\n", read("renamed/b2.txt"));
        assertTrue(repo.exists(Revision.INDEX, "renamed/b2.txt"));
    }

    @Test
    public void testExport() throws Exception
    {
        write("top.txt", "top\n");
        write("ui/main.bob", "<display/>\n");
        write("ui/sub/embedded.bob", "<display/>\n");
        write("ui/scripts/s.py", "print(1)\n");
        final Revision.Commit first = repo.resolve(commit("first"));
        write("ui/main.bob", "<display version='2'/>\n");
        commit("second");

        final File file = GitActions.exportVersion(repo, "ui/main.bob", first);
        try
        {
            assertEquals("<display/>\n", Files.readString(file.toPath()));
            final File top = file.getParentFile().getParentFile();
            assertTrue(new File(top, "ui/sub/embedded.bob").isFile());
            assertTrue(new File(top, "ui/scripts/s.py").isFile());
            // Only the directory of the file
            assertFalse(new File(top, "top.txt").exists());
            assertFalse(file.canWrite());
            assertThrows(Exception.class, () -> GitActions.exportVersion(repo, "ui/missing.bob", first));
        }
        finally
        {
            GitActions.deleteExported();
        }
        assertFalse(file.exists());
    }

    @Test
    public void testConflictStages() throws Exception
    {
        write("a.txt", "base\n");
        commit("base");
        git.branchCreate().setName("other").call();
        write("a.txt", "ours\n");
        commit("ours");
        git.checkout().setName("other").call();
        write("a.txt", "theirs\n");
        commit("theirs");
        git.checkout().setName("master").call();
        final MergeResult result = git.merge().include(git.getRepository().resolve("other")).call();
        assertEquals(MergeResult.MergeStatus.CONFLICTING, result.getMergeStatus());

        assertEquals("base\n", text(repo.getContent(new Revision.Stage(1), "a.txt")));
        assertEquals("ours\n", text(repo.getContent(new Revision.Stage(2), "a.txt")));
        assertEquals("theirs\n", text(repo.getContent(new Revision.Stage(3), "a.txt")));
        assertFalse(repo.exists(Revision.INDEX, "a.txt"));
    }
}
