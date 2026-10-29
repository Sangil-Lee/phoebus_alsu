package org.phoebus.applications.git;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.lib.StoredConfig;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.phoebus.applications.git.GitRepository.PushOptions;
import org.phoebus.applications.git.GitRepository.StatusEntry;

/** Tests of daily operations: Push options, pull/rebase, search, conflicts, hunks, ... */
@SuppressWarnings("nls")
public class GitRepositoryDailyTest
{
    @TempDir
    Path tmp;

    private File remote_dir;

    /** Second clone, used like a colleague who pushes to the same remote */
    private Git other;

    private File dir;
    private Git git;
    private GitRepository repo;

    private static void configureUser(final Repository repository, final String name) throws Exception
    {
        final StoredConfig config = repository.getConfig();
        config.setString("user", null, "name", name);
        config.setString("user", null, "email", name.toLowerCase() + "@example.com");
        config.save();
    }

    @BeforeEach
    public void setup() throws Exception
    {
        remote_dir = tmp.resolve("remote.git").toFile();
        Git.init().setDirectory(remote_dir).setBare(true).setInitialBranch("master").call().close();
        other = Git.cloneRepository().setURI(remote_dir.toURI().toString()).setDirectory(tmp.resolve("other").toFile()).call();
        configureUser(other.getRepository(), "Other");
        Files.writeString(tmp.resolve("other/a.txt"), "1\n2\n3\n");
        other.add().addFilepattern(".").call();
        other.commit().setMessage("base").call();
        other.push().call();

        dir = tmp.resolve("repo").toFile();
        git = Git.cloneRepository().setURI(remote_dir.toURI().toString()).setDirectory(dir).call();
        configureUser(git.getRepository(), "Tester");
        repo = new GitRepository(dir);
    }

    @AfterEach
    public void cleanup()
    {
        repo.close();
        git.close();
        other.close();
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

    private RevCommit commit(final Git g, final String file, final String text, final String message) throws Exception
    {
        Files.writeString(g.getRepository().getWorkTree().toPath().resolve(file), text);
        g.add().addFilepattern(".").call();
        return g.commit().setMessage(message).call();
    }

    private String remoteHead(final String branch) throws Exception
    {
        try (Git remote = Git.open(remote_dir))
        {
            final var id = remote.getRepository().resolve(Constants.R_HEADS + branch);
            return id == null ? null : id.name();
        }
    }

    // --- Push ---------------------------------------------------------------------

    @Test
    public void testPushNewBranchSetsUpstream() throws Exception
    {
        repo.createBranch("feature", null, true);
        commit(git, "f.txt", "f\n", "feature work");
        assertNull(repo.getBranches().stream().filter(b -> b.name().equals("feature")).findFirst().get().upstream());

        repo.push(PushOptions.DEFAULT, null);
        assertEquals(git.getRepository().resolve("feature").name(), remoteHead("feature"));
        assertEquals("origin/feature", repo.getBranches().stream().filter(b -> b.name().equals("feature")).findFirst().get().upstream());

        // Later pushes and status use the upstream
        commit(git, "f.txt", "f2\n", "more feature work");
        repo.fetch(null);
        assertEquals(1, repo.getBranches().stream().filter(b -> b.name().equals("feature")).findFirst().get().ahead());
        repo.push(PushOptions.DEFAULT, null);
        assertEquals(git.getRepository().resolve("feature").name(), remoteHead("feature"));
    }

    @Test
    public void testUnpushedCommits() throws Exception
    {
        assertEquals(List.of(), repo.getUnpushedCommits(PushOptions.DEFAULT));
        final RevCommit one = commit(git, "a.txt", "one\n", "one");
        final RevCommit two = commit(git, "a.txt", "two\n", "two");
        assertEquals(List.of(two.name(), one.name()), repo.getUnpushedCommits(PushOptions.DEFAULT));
        repo.push(PushOptions.DEFAULT, null);
        assertEquals(List.of(), repo.getUnpushedCommits(PushOptions.DEFAULT));

        // New branch without upstream: Only commits that no remote branch has
        repo.createBranch("feature", null, true);
        final RevCommit three = commit(git, "f.txt", "f\n", "three");
        assertEquals(List.of(three.name()), repo.getUnpushedCommits(PushOptions.DEFAULT));
        assertEquals("feature", repo.getPushTarget(PushOptions.DEFAULT).remote_branch());
        assertFalse(repo.getPushTarget(PushOptions.DEFAULT).has_upstream());
    }

    @Test
    public void testPushToUpstreamWithOtherName() throws Exception
    {
        // Local 'mine' tracks remote 'master'
        repo.renameBranch("master", "mine");
        assertEquals("origin/master", repo.getBranches().get(0).upstream());
        commit(git, "a.txt", "changed\n", "change");
        repo.push(PushOptions.DEFAULT, null);
        assertEquals(git.getRepository().resolve("mine").name(), remoteHead("master"));
        assertNull(remoteHead("mine"));
    }

    @Test
    public void testPushTags() throws Exception
    {
        repo.createTag("v1", "Release 1", null);
        repo.push(new PushOptions(null, null, true, false), null);
        try (Git remote = Git.open(remote_dir))
        {
            assertTrue(remote.getRepository().findRef("refs/tags/v1") != null);
        }
    }

    @Test
    public void testForcePushWithLease() throws Exception
    {
        commit(git, "a.txt", "mine\n", "mine");
        repo.push(PushOptions.DEFAULT, null);
        // Amend the pushed commit: Normal push is rejected
        repo.commit("mine, amended", true);
        assertThrows(Exception.class, () -> repo.push(PushOptions.DEFAULT, null));
        // Force push replaces the remote branch
        repo.push(new PushOptions(null, null, false, true), null);
        assertEquals(git.getRepository().resolve("master").name(), remoteHead("master"));

        // Colleague pushes, we didn't fetch: Force push must not overwrite that
        other.pull().call();
        commit(other, "b.txt", "other\n", "colleague's work");
        other.push().setForce(true).call();
        repo.commit("mine, amended again", true);
        final Exception error = assertThrows(Exception.class, () -> repo.push(new PushOptions(null, null, false, true), null));
        assertTrue(error.getMessage().contains("REJECTED"), error.getMessage());
        assertEquals(other.getRepository().resolve("master").name(), remoteHead("master"));
    }

    // --- Pull with rebase, rebase ---------------------------------------------------------

    @Test
    public void testPullRebase() throws Exception
    {
        commit(other, "b.txt", "remote\n", "remote change");
        other.push().call();
        commit(git, "c.txt", "local\n", "local change");

        assertFalse(repo.pull(null, true));
        // Linear history: Local commit on top of remote commit
        try (RevWalk walk = new RevWalk(git.getRepository()))
        {
            final RevCommit head = walk.parseCommit(git.getRepository().resolve(Constants.HEAD));
            assertEquals("local change", head.getShortMessage());
            assertEquals(1, head.getParentCount());
            assertEquals("remote change", walk.parseCommit(head.getParent(0)).getShortMessage());
        }
    }

    @Test
    public void testRebaseConflictContinueAbortSkip() throws Exception
    {
        commit(other, "a.txt", "1\nTHEIRS\n3\n", "remote change");
        other.push().call();
        commit(git, "a.txt", "1\nOURS\n3\n", "local change");
        repo.fetch(null);

        // Conflict, then abort restores the local commit
        assertTrue(repo.rebase("origin/master"));
        var status = repo.getStatus();
        assertTrue(status.rebasing());
        assertFalse(status.merging());
        assertEquals(List.of("Conflict:a.txt"), status.unstaged().stream().map(e -> e.type() + ":" + e.path()).toList());
        repo.abortRebase();
        assertFalse(repo.getStatus().rebasing());
        assertEquals("1\nOURS\n3\n", read("a.txt"));

        // Conflict, resolve by editing, continue
        assertTrue(repo.rebase("origin/master"));
        write("a.txt", "1\nBOTH\n3\n");
        repo.stage(List.of("a.txt"));
        assertFalse(repo.continueRebase());
        assertFalse(repo.getStatus().rebasing());
        assertEquals("local change", repo.getHeadMessage().trim());
        assertEquals("1\nBOTH\n3\n", read("a.txt"));

        // Conflict, skip drops the local commit
        commit(other, "a.txt", "1\nTHEIRS 2\n3\n", "remote change 2");
        other.push().call();
        commit(git, "a.txt", "1\nOURS 2\n3\n", "local change 2");
        repo.fetch(null);
        assertTrue(repo.rebase("origin/master"));
        // Two local commits are replayed ('local change' was never pushed), both conflict:
        // Skipping the first stops at the second
        assertTrue(repo.skipRebase());
        assertTrue(repo.getStatus().rebasing());
        assertFalse(repo.skipRebase());
        assertFalse(repo.getStatus().rebasing());
        assertEquals("remote change 2", repo.getHeadMessage().trim());
        assertEquals("1\nTHEIRS 2\n3\n", read("a.txt"));
    }

    @Test
    public void testBranchListsWithDetachedHead() throws Exception
    {
        commit(other, "a.txt", "1\nTHEIRS\n3\n", "remote change");
        other.push().call();
        commit(git, "a.txt", "1\nOURS\n3\n", "local change");
        repo.fetch(null);
        // During the rebase, HEAD is detached
        assertTrue(repo.rebase("origin/master"));
        assertEquals(List.of("master"), repo.getBranches().stream().map(b -> b.name()).toList());
        assertEquals(List.of("master"), repo.getRemoteBranches().get("origin").stream().map(b -> b.name()).toList());
        assertTrue(repo.getState(10, true).status().rebasing());
        repo.abortRebase();
    }

    @Test
    public void testRebaseNeedsCleanWorkTree() throws Exception
    {
        commit(other, "b.txt", "remote\n", "remote change");
        other.push().call();
        commit(git, "c.txt", "local\n", "local change");
        repo.fetch(null);
        write("a.txt", "uncommitted\n");
        assertThrows(Exception.class, () -> repo.rebase("origin/master"));
        assertFalse(repo.getStatus().rebasing());
    }

    // --- Search -------------------------------------------------------------------------

    @Test
    public void testSearch() throws Exception
    {
        final RevCommit fix = commit(git, "a.txt", "fixed\n", "Fix PV name in TEMP record");
        git.commit().setAuthor(new PersonIdent("Kim Operator", "kim@example.com")).setAllowEmpty(true).setMessage("Adjust alarm limits").call();
        commit(git, "a.txt", "more\n", "Unrelated");

        assertEquals(List.of("Fix PV name in TEMP record"),
                     repo.getLog(100, true, "pv name").stream().map(c -> c.message()).toList());
        assertEquals(List.of("Adjust alarm limits"),
                     repo.getLog(100, true, "kim").stream().map(c -> c.message()).toList());
        assertEquals(List.of("Fix PV name in TEMP record"),
                     repo.getLog(100, true, fix.name().substring(0, 8)).stream().map(c -> c.message()).toList());
        assertEquals(5, repo.getLog(100, true, "").size() + 1);
    }

    // --- Conflicts ------------------------------------------------------------------------

    @Test
    public void testResolveConflictOursTheirs() throws Exception
    {
        git.checkout().setCreateBranch(true).setName("theirs").call();
        commit(git, "a.txt", "1\nTHEIRS\n3\n", "theirs");
        commit(git, "gone.txt", "x\n", "add file");
        git.checkout().setName("master").call();
        commit(git, "a.txt", "1\nOURS\n3\n", "ours");
        commit(git, "gone.txt", "ours\n", "ours adds same file");

        assertTrue(repo.merge("theirs", false));
        repo.resolveConflict("a.txt", false);
        assertEquals("1\nTHEIRS\n3\n", read("a.txt"));
        repo.resolveConflict("gone.txt", true);
        assertEquals("ours\n", read("gone.txt"));
        final var status = repo.getStatus();
        assertEquals(List.of(), status.unstaged());
        repo.commit("Merge", false);
        assertFalse(repo.getStatus().merging());
    }

    // --- Hunks ------------------------------------------------------------------------------

    private static String lines(final int from, final int to, final String suffix)
    {
        final StringBuilder buf = new StringBuilder();
        for (int i=from; i<=to; ++i)
            buf.append("line ").append(i).append(i == 3 || i == 18 ? suffix : "").append('\n');
        return buf.toString();
    }

    @Test
    public void testHunks() throws Exception
    {
        commit(git, "h.txt", lines(1, 20, ""), "base");
        // Two changes far apart, so two hunks
        write("h.txt", lines(1, 20, " changed"));
        assertEquals(2, repo.getUnstagedDiff("h.txt").lines().filter(l -> l.startsWith("@@")).count());

        repo.stageHunk("h.txt", 1);
        String staged = repo.getStagedDiff("h.txt");
        assertTrue(staged.contains("+line 18 changed"));
        assertFalse(staged.contains("+line 3 changed"));
        String unstaged = repo.getUnstagedDiff("h.txt");
        assertTrue(unstaged.contains("+line 3 changed"));
        assertFalse(unstaged.contains("line 18 changed"));

        repo.unstageHunk("h.txt", 0);
        assertEquals("", repo.getStagedDiff("h.txt"));

        // Discard the first hunk in the work tree, second stays
        repo.discardHunk("h.txt", 0);
        assertTrue(read("h.txt").contains("line 3\n"));
        assertTrue(read("h.txt").contains("line 18 changed\n"));

        // Diff from before the file changed again does not apply
        write("h.txt", lines(1, 20, "").replace("line 17", "line 17 edited").replace("line 18", "line 18 changed"));
        repo.stageHunk("h.txt", 0);
        assertTrue(repo.getStagedDiff("h.txt").contains("+line 17 edited"));
    }

    @Test
    public void testHunkStaleIsRejected() throws Exception
    {
        commit(git, "h.txt", lines(1, 20, ""), "base");
        write("h.txt", lines(1, 20, " changed"));
        // Index changes behind the back of the diff: Hunk 1 no longer matches
        repo.stageHunk("h.txt", 0);
        repo.stageHunk("h.txt", 0);
        assertEquals("", repo.getUnstagedDiff("h.txt"));
        assertThrows(Exception.class, () -> repo.stageHunk("h.txt", 0));
    }

    @Test
    public void testHunkWithCrLfAndNoNewline() throws Exception
    {
        final Path file = dir.toPath().resolve("w.txt");
        Files.write(file, "a\r\nb\r\nc\r\nd\r\ne\r\nf\r\ng\r\nh\r\ni\r\nj".getBytes(StandardCharsets.UTF_8));
        git.add().addFilepattern(".").call();
        git.commit().setMessage("crlf").call();
        Files.write(file, "a\r\nB\r\nc\r\nd\r\ne\r\nf\r\ng\r\nh\r\ni\r\nJ".getBytes(StandardCharsets.UTF_8));
        assertEquals(2, repo.getUnstagedDiff("w.txt").lines().filter(l -> l.startsWith("@@")).count());

        repo.stageHunk("w.txt", 1);
        final byte[] index = git.getRepository().open(git.getRepository().readDirCache().getEntry("w.txt").getObjectId()).getBytes();
        assertEquals("a\r\nb\r\nc\r\nd\r\ne\r\nf\r\ng\r\nh\r\ni\r\nJ", new String(index, StandardCharsets.UTF_8));

        repo.discardHunk("w.txt", 0);
        assertEquals("a\r\nb\r\nc\r\nd\r\ne\r\nf\r\ng\r\nh\r\ni\r\nJ", Files.readString(file));
    }

    @Test
    public void testStageHunkOfUntrackedFile() throws Exception
    {
        write("new.txt", "new\n");
        repo.stageHunk("new.txt", 0);
        assertEquals(List.of("Added:new.txt"),
                     repo.getStatus().staged().stream().map(e -> e.type() + ":" + e.path()).toList());
        assertEquals(List.of(), repo.getStatus().unstaged().stream().map(StatusEntry::path).filter(p -> p.equals("new.txt")).toList());
    }

    // --- Versions for widget diff ------------------------------------------------------------------

    @Test
    public void testVersions() throws Exception
    {
        commit(git, "d.bob", "<display>1</display>", "base display");
        write("d.bob", "<display>2</display>");
        repo.stage(List.of("d.bob"));
        write("d.bob", "<display>3</display>");

        var versions = repo.getStagedVersions("d.bob");
        assertEquals("<display>1</display>", new String(versions.old_content()));
        assertEquals("<display>2</display>", new String(versions.new_content()));
        versions = repo.getUnstagedVersions("d.bob");
        assertEquals("<display>2</display>", new String(versions.old_content()));
        assertEquals("<display>3</display>", new String(versions.new_content()));
        versions = repo.getUncommittedVersions("d.bob");
        assertEquals("<display>1</display>", new String(versions.old_content()));
        assertEquals("<display>3</display>", new String(versions.new_content()));
        // New file: No old content
        write("new.bob", "<display>new</display>");
        assertEquals(0, repo.getUncommittedVersions("new.bob").old_content().length);

        // Commit with rename and change: Old content from the old path
        repo.stage(List.of("d.bob"));
        repo.commit("change", false);
        Files.move(dir.toPath().resolve("d.bob"), dir.toPath().resolve("renamed.bob"));
        write("renamed.bob", "<display>3</display>");
        git.add().addFilepattern(".").call();
        git.add().setUpdate(true).addFilepattern(".").call();
        final RevCommit rename = git.commit().setMessage("rename").call();
        versions = repo.getCommitFileVersions(rename.name(), "renamed.bob");
        assertEquals("<display>3</display>", new String(versions.old_content()));
        assertEquals("<display>3</display>", new String(versions.new_content()));
        // Deleted file: No new content
        Files.delete(dir.toPath().resolve("renamed.bob"));
        git.add().setUpdate(true).addFilepattern(".").call();
        final RevCommit delete = git.commit().setMessage("delete").call();
        versions = repo.getCommitFileVersions(delete.name(), "renamed.bob");
        assertEquals("<display>3</display>", new String(versions.old_content()));
        assertEquals(0, versions.new_content().length);
    }

    // --- Rename branch, ignore, white space -----------------------------------------------------

    @Test
    public void testRenameBranchAndIgnore() throws Exception
    {
        repo.createBranch("old_name", null, false);
        repo.renameBranch("old_name", "new_name");
        assertTrue(repo.getBranches().stream().anyMatch(b -> b.name().equals("new_name")));
        assertFalse(repo.getBranches().stream().anyMatch(b -> b.name().equals("old_name")));

        write("build/out.bin", "x");
        write("debug.log", "x");
        assertEquals(2, repo.getStatus().unstaged().size());
        repo.addToIgnore("build/");
        repo.addToIgnore("*.log");
        assertEquals(List.of("Untracked:.gitignore"),
                     repo.getStatus().unstaged().stream().map(e -> e.type() + ":" + e.path()).toList());
        assertEquals("build/\n*.log\n", read(".gitignore"));
    }

    @Test
    public void testIgnoreWhitespace() throws Exception
    {
        commit(git, "a.txt", "a b\nsame\n", "base");
        write("a.txt", "a   b\nsame\n");
        assertTrue(repo.getUnstagedDiff("a.txt").contains("+a   b"));
        repo.setIgnoreWhitespace(true);
        assertFalse(repo.getUnstagedDiff("a.txt").contains("+a   b"));
        // Hunk operations use the complete diff
        repo.stageHunk("a.txt", 0);
        repo.setIgnoreWhitespace(false);
        assertTrue(repo.getStagedDiff("a.txt").contains("+a   b"));
    }
}
