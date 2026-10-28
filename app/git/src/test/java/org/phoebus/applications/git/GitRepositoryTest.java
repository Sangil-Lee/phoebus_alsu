package org.phoebus.applications.git;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.NotMergedException;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.lib.StoredConfig;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.phoebus.applications.git.GitRepository.CommitInfo;
import org.phoebus.applications.git.GitRepository.FileStatus;
import org.phoebus.applications.git.GitRepository.ResetMode;
import org.phoebus.applications.git.GitRepository.StatusEntry;

/** Tests of {@link GitRepository} on temporary repositories
 *
 *  <p>Repositories are set up with JGit, no 'git' command needed.
 */
@SuppressWarnings("nls")
public class GitRepositoryTest
{
    @TempDir
    Path tmp;

    /** Work tree of the repository under test */
    private File dir;

    /** JGit access to the same repository, used to set up and check */
    private Git git;

    private GitRepository repo;

    @BeforeEach
    public void setup() throws Exception
    {
        dir = tmp.resolve("repo").toFile();
        git = Git.init().setDirectory(dir).setInitialBranch("master").call();
        configureUser(git.getRepository());
        repo = new GitRepository(dir);
    }

    @AfterEach
    public void cleanup()
    {
        repo.close();
        git.close();
    }

    private static void configureUser(final Repository repository) throws Exception
    {
        final StoredConfig config = repository.getConfig();
        config.setString("user", null, "name", "Tester");
        config.setString("user", null, "email", "tester@example.com");
        config.save();
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

    /** Stage everything and commit with JGit, independent of the code under test */
    private RevCommit commitAll(final String message) throws Exception
    {
        git.add().addFilepattern(".").call();
        git.add().setUpdate(true).addFilepattern(".").call();
        return git.commit().setMessage(message).call();
    }

    private static List<String> describe(final List<StatusEntry> entries)
    {
        return entries.stream().map(e -> e.type() + ":" + e.path()).toList();
    }

    private int countHeadParents() throws Exception
    {
        try (RevWalk walk = new RevWalk(git.getRepository()))
        {
            return walk.parseCommit(git.getRepository().resolve(Constants.HEAD)).getParentCount();
        }
    }

    // --- File status ------------------------------------------------------------

    @Test
    public void testStatusStageUnstage() throws Exception
    {
        write("modified.txt", "1\n");
        write("deleted.txt", "1\n");
        commitAll("base");

        write("modified.txt", "2\n");
        Files.delete(dir.toPath().resolve("deleted.txt"));
        write("sub/new.txt", "new\n");

        FileStatus status = repo.getStatus();
        assertEquals(List.of(), describe(status.staged()));
        assertEquals(List.of("Deleted:deleted.txt", "Modified:modified.txt", "Untracked:sub/new.txt"),
                     describe(status.unstaged()));
        assertFalse(status.merging());

        repo.stage(List.of("modified.txt", "deleted.txt", "sub/new.txt"));
        status = repo.getStatus();
        assertEquals(List.of("Deleted:deleted.txt", "Modified:modified.txt", "Added:sub/new.txt"),
                     describe(status.staged()));
        assertEquals(List.of(), describe(status.unstaged()));

        repo.unstage(List.of("deleted.txt", "sub/new.txt"));
        status = repo.getStatus();
        assertEquals(List.of("Modified:modified.txt"), describe(status.staged()));
        assertEquals(List.of("Deleted:deleted.txt", "Untracked:sub/new.txt"), describe(status.unstaged()));
    }

    @Test
    public void testPartiallyStagedDiffs() throws Exception
    {
        write("a.txt", "one\ntwo\nthree\n");
        commitAll("base");

        write("a.txt", "one\nTWO\nthree\n");
        repo.stage(List.of("a.txt"));
        write("a.txt", "one\nTWO\nthree\nfour\n");

        final FileStatus status = repo.getStatus();
        assertEquals(List.of("Modified:a.txt"), describe(status.staged()));
        assertEquals(List.of("Modified:a.txt"), describe(status.unstaged()));

        final String staged = repo.getStagedDiff("a.txt");
        assertTrue(staged.contains("-two"));
        assertTrue(staged.contains("+TWO"));
        assertFalse(staged.contains("+four"));

        final String unstaged = repo.getUnstagedDiff("a.txt");
        assertTrue(unstaged.contains("+four"));
        assertFalse(unstaged.contains("+TWO"));
    }

    @Test
    public void testCommitOnlyStagedAndAmend() throws Exception
    {
        write("a.txt", "1\n");
        commitAll("base");

        assertThrows(Exception.class, () -> repo.commit("nothing", false));

        write("a.txt", "2\n");
        write("b.txt", "unstaged\n");
        repo.stage(List.of("a.txt"));
        repo.commit("change a", false);

        assertEquals("change a", repo.getHeadMessage());
        assertEquals(List.of("Untracked:b.txt"), describe(repo.getStatus().unstaged()));

        // Amend only changes the message, number of commits stays the same
        repo.commit("change a, amended", true);
        final List<CommitInfo> log = repo.getLog(10, false);
        assertEquals(List.of("change a, amended", "base"), log.stream().map(CommitInfo::message).toList());
    }

    @Test
    public void testDiscard() throws Exception
    {
        write("modified.txt", "original\n");
        write("deleted.txt", "original\n");
        commitAll("base");

        write("modified.txt", "changed\n");
        Files.delete(dir.toPath().resolve("deleted.txt"));
        write("untracked.txt", "junk\n");

        repo.discard(List.of("modified.txt", "deleted.txt", "untracked.txt"));

        assertEquals("original\n", read("modified.txt"));
        assertEquals("original\n", read("deleted.txt"));
        assertFalse(new File(dir, "untracked.txt").exists());
        assertEquals(List.of(), describe(repo.getStatus().unstaged()));
    }

    @Test
    public void testDiscardRemovesEmptyDirectories() throws Exception
    {
        write("keep/a.txt", "1\n");
        commitAll("base");

        write("new/deep/x.txt", "x\n");
        write("keep/untracked.txt", "u\n");
        repo.discard(List.of("new/deep/x.txt", "keep/untracked.txt"));

        assertFalse(new File(dir, "new").exists());
        // Directory with tracked file stays
        assertTrue(new File(dir, "keep/a.txt").exists());
        assertFalse(new File(dir, "keep/untracked.txt").exists());
    }

    @Test
    public void testUncommittedDiff() throws Exception
    {
        write("a.txt", "1\n");
        commitAll("base");
        write("a.txt", "staged\n");
        repo.stage(List.of("a.txt"));
        write("a.txt", "staged and more\n");
        write("b.txt", "new\n");
        write("ignored.log", "ignored\n");
        write(".gitignore", "*.log\n");

        final String diff = repo.getUncommittedDiff(List.of("a.txt", "b.txt"));
        // HEAD to work tree, so staged and unstaged changes combined
        assertTrue(diff.contains("+staged and more"));
        assertTrue(diff.contains("+new"));
        assertFalse(diff.contains("ignored"));
        assertEquals("", repo.getUncommittedDiff(List.of()));
    }

    @Test
    public void testEmptyRepository() throws Exception
    {
        assertEquals(List.of(), repo.getLog(10, true));
        assertNull(repo.getHeadMessage());

        write("first.txt", "first\n");
        repo.stage(List.of("first.txt"));
        assertEquals(List.of("Added:first.txt"), describe(repo.getStatus().staged()));
        assertTrue(repo.getStagedDiff("first.txt").contains("+first"));

        // No HEAD to reset to, unstage removes from index
        repo.unstage(List.of("first.txt"));
        assertEquals(List.of("Untracked:first.txt"), describe(repo.getStatus().unstaged()));

        repo.stage(List.of("first.txt"));
        repo.commit("first", false);
        assertEquals(List.of("first"), repo.getLog(10, true).stream().map(CommitInfo::message).toList());
    }

    // --- Branches, merge, cherry-pick, revert ----------------------------------

    @Test
    public void testBranches() throws Exception
    {
        write("a.txt", "1\n");
        commitAll("base");

        repo.createBranch("feature", null, true);
        assertEquals("feature", repo.getBranch());
        write("f.txt", "f\n");
        commitAll("feature work");

        repo.createBranch("other", "master", false);
        assertEquals("feature", repo.getBranch());

        repo.checkout("master");
        assertEquals("master", repo.getBranch());
        assertEquals(List.of("feature", "master", "other"),
                     repo.getBranches().stream().map(b -> b.name()).toList());
        assertTrue(repo.getBranches().stream().filter(b -> b.name().equals("master")).findFirst().get().current());

        // 'feature' has a commit not on master
        assertThrows(NotMergedException.class, () -> repo.deleteBranch("feature", false));
        repo.deleteBranch("feature", true);
        repo.deleteBranch("other", false);
        assertEquals(List.of("master"), repo.getBranches().stream().map(b -> b.name()).toList());
    }

    @Test
    public void testMerge() throws Exception
    {
        write("a.txt", "1\n2\n3\n");
        commitAll("base");

        // Fast-forward
        git.checkout().setCreateBranch(true).setName("ff").call();
        write("ff.txt", "ff\n");
        commitAll("ff change");
        git.checkout().setName("master").call();
        assertFalse(repo.merge("ff", false));
        assertEquals(1, countHeadParents());

        // No fast-forward creates a merge commit
        git.checkout().setCreateBranch(true).setName("side").call();
        write("side.txt", "s\n");
        commitAll("side change");
        git.checkout().setName("master").call();
        assertFalse(repo.merge("side", true));
        assertEquals(2, countHeadParents());
    }

    @Test
    public void testMergeConflictAndAbort() throws Exception
    {
        write("a.txt", "1\n2\n3\n");
        commitAll("base");
        git.checkout().setCreateBranch(true).setName("theirs").call();
        write("a.txt", "1\nTHEIRS\n3\n");
        commitAll("theirs");
        git.checkout().setName("master").call();
        write("a.txt", "1\nOURS\n3\n");
        commitAll("ours");

        assertTrue(repo.merge("theirs", false));
        FileStatus status = repo.getStatus();
        assertTrue(status.merging());
        assertEquals(List.of("Conflict:a.txt"), describe(status.unstaged()));
        // Prepared message without the '# Conflicts' comment
        assertEquals("Merge branch 'theirs'", status.merge_message());

        // Conflict diff is shown once, from HEAD to the file with markers
        final String diff = repo.getUnstagedDiff("a.txt");
        assertEquals(1, diff.lines().filter(line -> line.startsWith("diff --git")).count());
        assertTrue(diff.contains("+<<<<<<< HEAD"));

        repo.abortMerge();
        status = repo.getStatus();
        assertFalse(status.merging());
        assertEquals(List.of(), describe(status.unstaged()));
        assertEquals("1\nOURS\n3\n", read("a.txt"));
    }

    @Test
    public void testResolveConflictAndCommit() throws Exception
    {
        write("a.txt", "base\n");
        commitAll("base");
        git.checkout().setCreateBranch(true).setName("theirs").call();
        write("a.txt", "theirs\n");
        commitAll("theirs");
        git.checkout().setName("master").call();
        write("a.txt", "ours\n");
        commitAll("ours");

        assertTrue(repo.merge("theirs", false));
        // Resolve by keeping our version: Nothing staged, but commit concludes the merge
        write("a.txt", "ours\n");
        repo.stage(List.of("a.txt"));
        assertEquals(List.of(), describe(repo.getStatus().staged()));
        repo.commit("Merge branch 'theirs'", false);
        assertEquals(2, countHeadParents());
        assertFalse(repo.getStatus().merging());
    }

    @Test
    public void testMergeWithLocalChangesFails() throws Exception
    {
        write("a.txt", "1\n");
        commitAll("base");
        git.checkout().setCreateBranch(true).setName("other").call();
        write("a.txt", "other\n");
        commitAll("other");
        git.checkout().setName("master").call();

        write("a.txt", "local, not committed\n");
        assertThrows(Exception.class, () -> repo.merge("other", false));
        assertEquals("local, not committed\n", read("a.txt"));
    }

    @Test
    public void testCherryPickAndRevert() throws Exception
    {
        write("a.txt", "1\n");
        commitAll("base");
        git.checkout().setCreateBranch(true).setName("pick").call();
        write("p.txt", "picked\n");
        final RevCommit pick = commitAll("pick me");
        git.checkout().setName("master").call();

        assertFalse(repo.cherryPick(pick.name()));
        assertEquals("picked\n", read("p.txt"));
        assertEquals("pick me", repo.getHeadMessage().trim());

        final ObjectId head = git.getRepository().resolve(Constants.HEAD);
        assertFalse(repo.revert(head.name()));
        assertFalse(new File(dir, "p.txt").exists());
        assertTrue(repo.getHeadMessage().startsWith("Revert \"pick me\""));
    }

    @Test
    public void testRevertConflictAndAbort() throws Exception
    {
        write("a.txt", "1\n");
        commitAll("base");
        write("a.txt", "2\n");
        final RevCommit change = commitAll("change to 2");
        write("a.txt", "3\n");
        commitAll("change to 3");

        assertTrue(repo.revert(change.name()));
        final FileStatus status = repo.getStatus();
        assertTrue(status.merging());
        assertTrue(status.merge_message().startsWith("Revert \"change to 2\""));

        repo.abortMerge();
        assertFalse(repo.getStatus().merging());
        assertEquals("3\n", read("a.txt"));
    }

    @Test
    public void testReset() throws Exception
    {
        write("a.txt", "1\n");
        final RevCommit base = commitAll("base");
        write("a.txt", "2\n");
        commitAll("second");

        // Soft: Branch moves, change stays staged
        repo.reset(base.name(), ResetMode.SOFT);
        assertEquals("base", repo.getHeadMessage());
        assertEquals(List.of("Modified:a.txt"), describe(repo.getStatus().staged()));

        // Mixed: Index reset, change stays in work tree
        repo.reset(base.name(), ResetMode.MIXED);
        assertEquals(List.of(), describe(repo.getStatus().staged()));
        assertEquals(List.of("Modified:a.txt"), describe(repo.getStatus().unstaged()));

        // Hard: Change is gone
        repo.reset(base.name(), ResetMode.HARD);
        assertEquals(List.of(), describe(repo.getStatus().unstaged()));
        assertEquals("1\n", read("a.txt"));
    }

    // --- Tags, stash, history ----------------------------------------------------

    @Test
    public void testTags() throws Exception
    {
        write("a.txt", "1\n");
        final RevCommit first = commitAll("first");
        write("a.txt", "2\n");
        final RevCommit second = commitAll("second");

        repo.createTag("light", "", null);
        repo.createTag("annotated", "Release", first.name());

        final Repository repository = git.getRepository();
        assertEquals(Constants.OBJ_COMMIT, repository.open(repository.resolve("refs/tags/light")).getType());
        assertEquals(Constants.OBJ_TAG, repository.open(repository.resolve("refs/tags/annotated")).getType());

        // Tag list reports the tagged commit, also for the annotated tag
        assertEquals(List.of("annotated=" + first.name(), "light=" + second.name()),
                     repo.getTags().stream().map(t -> t.name() + "=" + t.commit()).toList());

        repo.deleteTag("light");
        assertEquals(List.of("annotated"), repo.getTags().stream().map(t -> t.name()).toList());
    }

    @Test
    public void testStash() throws Exception
    {
        write("a.txt", "1\n");
        commitAll("base");

        assertThrows(Exception.class, () -> repo.stash(null, false));

        write("a.txt", "changed\n");
        write("untracked.txt", "untracked\n");
        repo.stash("my stash", true);
        assertEquals("1\n", read("a.txt"));
        assertFalse(new File(dir, "untracked.txt").exists());
        assertEquals(List.of("my stash"), repo.getStashes().stream().map(s -> s.message()).toList());

        // Stash diff includes the untracked file
        final String diff = repo.getStashDiff(repo.getStashes().get(0).commit());
        assertTrue(diff.contains("+changed"));
        assertTrue(diff.contains("+untracked"));

        repo.applyStash(0, true);
        assertEquals("changed\n", read("a.txt"));
        assertEquals("untracked\n", read("untracked.txt"));
        assertEquals(List.of(), repo.getStashes());
    }

    @Test
    public void testLogAllBranches() throws Exception
    {
        write("a.txt", "1\n");
        commitAll("base");
        git.checkout().setCreateBranch(true).setName("side").call();
        write("b.txt", "b\n");
        commitAll("side work");
        git.checkout().setName("master").call();
        write("a.txt", "2\n");
        commitAll("master work");

        final List<CommitInfo> current = repo.getLog(10, false);
        assertEquals(List.of("master work", "base"), current.stream().map(CommitInfo::message).toList());

        final List<CommitInfo> all = repo.getLog(10, true);
        assertEquals(3, all.size());
        assertTrue(all.stream().anyMatch(c -> c.message().equals("side work")));
        // Two branches need two lanes
        assertEquals(2, all.stream().map(c -> c.plot().getLane().getPosition()).distinct().count());
        // Number of commits is limited as requested
        assertEquals(2, repo.getLog(2, true).size());
        assertEquals(1, repo.getLog(1, false).size());
    }

    @Test
    public void testCommitDiff() throws Exception
    {
        write("a.txt", "1\n");
        final RevCommit first = commitAll("first");
        write("a.txt", "2\n");
        final RevCommit second = commitAll("second");

        // First commit is compared with the empty tree
        final String first_diff = repo.getCommitDiff(first.name());
        assertTrue(first_diff.contains("new file mode"));
        assertTrue(first_diff.contains("+1"));

        final String second_diff = repo.getCommitDiff(second.name());
        assertTrue(second_diff.startsWith("commit " + second.name()));
        assertTrue(second_diff.contains("-1"));
        assertTrue(second_diff.contains("+2"));
    }

    // --- Commit files, file history, blame ----------------------------------------

    @Test
    public void testCommitFiles() throws Exception
    {
        write("keep.txt", "keep\n");
        write("old_name.txt", "same content\nfor rename detection\nmore lines\n");
        write("gone.txt", "gone\n");
        final RevCommit first = commitAll("first");

        write("keep.txt", "changed\n");
        Files.move(dir.toPath().resolve("old_name.txt"), dir.toPath().resolve("new_name.txt"));
        Files.delete(dir.toPath().resolve("gone.txt"));
        write("added.txt", "added\n");
        final RevCommit second = commitAll("second");

        // First commit: All added
        assertEquals(List.of("Added:gone.txt", "Added:keep.txt", "Added:old_name.txt"),
                     repo.getCommitFiles(first.name()).stream().map(f -> f.type() + ":" + f.path()).toList());

        assertEquals(List.of("Added:added.txt", "Deleted:gone.txt", "Modified:keep.txt", "Renamed:new_name.txt<-old_name.txt"),
                     repo.getCommitFiles(second.name()).stream()
                         .map(f -> f.type() + ":" + f.path() + (f.old_path() == null ? "" : "<-" + f.old_path()))
                         .toList());

        // Diff of a single file
        final String keep = repo.getCommitFileDiff(second.name(), "keep.txt");
        assertTrue(keep.contains("-keep"));
        assertTrue(keep.contains("+changed"));
        assertFalse(keep.contains("added.txt"));
        // Rename can be found by old or new path
        assertTrue(repo.getCommitFileDiff(second.name(), "old_name.txt").contains("rename from old_name.txt"));
        assertTrue(repo.getCommitFileDiff(second.name(), "new_name.txt").contains("rename to new_name.txt"));
    }

    @Test
    public void testFileHistoryFollowsRename() throws Exception
    {
        write("other.txt", "other\n");
        commitAll("unrelated");
        write("old.db", "record 1\nrecord 2\nrecord 3\nrecord 4\n");
        commitAll("create old.db");
        write("old.db", "record 1\nrecord 2\nrecord 3\nrecord 4 changed\n");
        commitAll("change old.db");
        Files.move(dir.toPath().resolve("old.db"), dir.toPath().resolve("new.db"));
        commitAll("rename to new.db");
        write("new.db", "record 1 changed\nrecord 2\nrecord 3\nrecord 4 changed\n");
        commitAll("change new.db");
        write("other.txt", "other changed\n");
        commitAll("unrelated 2");

        final List<String> history = repo.getFileHistory("new.db", 100).stream()
                                         .map(v -> v.commit().message() + "@" + v.path())
                                         .toList();
        assertEquals(List.of("change new.db@new.db",
                             "rename to new.db@new.db",
                             "change old.db@old.db",
                             "create old.db@old.db"),
                     history);

        // Diff of each version uses the path at that time
        final var versions = repo.getFileHistory("new.db", 100);
        assertTrue(repo.getCommitFileDiff(versions.get(2).commit().id(), versions.get(2).path()).contains("+record 4 changed"));
        assertEquals(2, repo.getFileHistory("new.db", 2).size());
    }

    @Test
    public void testBlame() throws Exception
    {
        write("a.db", "line 1\nline 2\nline 3\n");
        final RevCommit first = commitAll("first");
        write("a.db", "line 1\nline 2 changed\nline 3\n");
        final RevCommit second = commitAll("second");
        // Uncommitted change in the work tree
        write("a.db", "line 1\nline 2 changed\nline 3 local\n");

        final List<GitRepository.BlameLine> lines = repo.blame("a.db");
        assertEquals(3, lines.size());
        assertEquals(first.name(), lines.get(0).commit());
        assertEquals("line 1", lines.get(0).text());
        assertEquals(second.name(), lines.get(1).commit());
        assertEquals("Tester", lines.get(1).author());
        assertEquals("line 3 local", lines.get(2).text());
        assertNull(lines.get(2).commit());
        assertEquals("", lines.get(2).author());

        write("untracked.db", "x\n");
        assertThrows(Exception.class, () -> repo.blame("untracked.db"));
    }

    @Test
    public void testInitAndClone() throws Exception
    {
        final File new_dir = tmp.resolve("new_repo").toFile();
        GitRepository.init(new_dir);
        assertTrue(new File(new_dir, ".git").isDirectory());
        assertThrows(Exception.class, () -> GitRepository.init(new_dir));

        write("a.txt", "1\n");
        commitAll("base");
        final File clone_dir = tmp.resolve("cloned").toFile();
        final List<String> tasks = new java.util.ArrayList<>();
        GitRepository.cloneRepository(dir.toURI().toString(), clone_dir, null,
                                      new org.eclipse.jgit.lib.EmptyProgressMonitor()
        {
            @Override
            public void beginTask(final String title, final int total)
            {
                tasks.add(title);
            }
        });
        assertEquals("1\n", Files.readString(clone_dir.toPath().resolve("a.txt")));
        assertFalse(tasks.isEmpty());
        try (GitRepository clone = new GitRepository(clone_dir))
        {
            assertEquals("origin/master", clone.getBranches().get(0).upstream());
        }
        // Clone into non-empty directory fails
        assertThrows(Exception.class, () -> GitRepository.cloneRepository(dir.toURI().toString(), clone_dir, null,
                                                                          org.eclipse.jgit.lib.NullProgressMonitor.INSTANCE));
    }

    // --- Remotes -------------------------------------------------------------------

    /** Create bare 'remote' with one commit, and clone it as the repository under test */
    private Git setupRemote() throws Exception
    {
        final File remote_dir = tmp.resolve("remote.git").toFile();
        Git.init().setDirectory(remote_dir).setBare(true).setInitialBranch("master").call().close();

        // Initial commit via a helper clone
        try (Git helper = Git.cloneRepository().setURI(remote_dir.toURI().toString())
                             .setDirectory(tmp.resolve("helper").toFile()).call())
        {
            configureUser(helper.getRepository());
            Files.writeString(tmp.resolve("helper/a.txt"), "1\n");
            helper.add().addFilepattern(".").call();
            helper.commit().setMessage("base").call();
            helper.push().call();
            helper.checkout().setCreateBranch(true).setName("feature").call();
            Files.writeString(tmp.resolve("helper/f.txt"), "f\n");
            helper.add().addFilepattern(".").call();
            helper.commit().setMessage("feature").call();
            helper.push().call();
        }

        // Replace the repository under test with a clone
        repo.close();
        git.close();
        dir = tmp.resolve("clone").toFile();
        git = Git.cloneRepository().setURI(remote_dir.toURI().toString()).setDirectory(dir).call();
        configureUser(git.getRepository());
        repo = new GitRepository(dir);

        return Git.open(tmp.resolve("helper").toFile());
    }

    @Test
    public void testPushPullFetch() throws Exception
    {
        try (Git helper = setupRemote())
        {
            assertEquals(List.of("feature", "master"),
                         repo.getRemoteBranches().get("origin").stream().map(b -> b.name()).toList());

            // Local commit is 'ahead', push
            write("a.txt", "local\n");
            repo.stage(List.of("a.txt"));
            repo.commit("local", false);
            assertEquals(1, repo.getBranches().get(0).ahead());
            repo.push(null);
            assertEquals(0, repo.getBranches().get(0).ahead());

            // Remote moves on: Fetch shows 'behind', pull gets it
            helper.checkout().setName("master").call();
            helper.pull().call();
            Files.writeString(tmp.resolve("helper/r.txt"), "remote\n");
            helper.add().addFilepattern(".").call();
            helper.commit().setMessage("remote").call();
            helper.push().call();
            repo.fetch(null);
            assertEquals(1, repo.getBranches().get(0).behind());
            repo.pull(null);
            assertEquals("remote\n", read("r.txt"));

            // Push is rejected when remote has commits we don't have
            Files.writeString(tmp.resolve("helper/r.txt"), "remote 2\n");
            helper.add().addFilepattern(".").call();
            helper.commit().setMessage("remote 2").call();
            helper.push().call();
            write("a.txt", "local 2\n");
            repo.stage(List.of("a.txt"));
            repo.commit("local 2", false);
            final Exception error = assertThrows(Exception.class, () -> repo.push(null));
            assertTrue(error.getMessage().contains("REJECTED_NONFASTFORWARD"));

            // Fetch removes branches that were deleted on the remote
            helper.push().setRefSpecs(new org.eclipse.jgit.transport.RefSpec(":refs/heads/feature")).call();
            repo.fetch(null);
            assertEquals(List.of("master"),
                         repo.getRemoteBranches().get("origin").stream().map(b -> b.name()).toList());
        }
    }

    @Test
    public void testCheckoutRemoteAndPushTag() throws Exception
    {
        try (Git helper = setupRemote())
        {
            final var feature = repo.getRemoteBranches().get("origin").stream()
                                    .filter(b -> b.name().equals("feature")).findFirst().get();
            repo.checkoutRemote(feature, "feature");
            assertEquals("feature", repo.getBranch());
            assertEquals("origin/feature", repo.getBranches().stream()
                                               .filter(b -> b.name().equals("feature"))
                                               .findFirst().get().upstream());

            repo.createTag("v1", "Release 1", null);
            repo.pushTag("v1", null);
            assertTrue(getRemoteTags().contains("refs/tags/v1"));

            // Deleting the remote tag keeps the local one
            repo.deleteRemoteTag("v1", null);
            assertFalse(getRemoteTags().contains("refs/tags/v1"));
            assertEquals(List.of("v1"), repo.getTags().stream().map(t -> t.name()).toList());
        }
    }

    @Test
    public void testRemoteManagement() throws Exception
    {
        assertEquals(java.util.Map.of(), repo.getRemoteUrls());
        repo.addRemote("origin", "https://example.com/group/repo.git");
        repo.addRemote("backup", "git@example.com:group/repo.git");
        assertThrows(Exception.class, () -> repo.addRemote("origin", "https://other"));
        assertEquals(List.of("backup=git@example.com:group/repo.git", "origin=https://example.com/group/repo.git"),
                     repo.getRemoteUrls().entrySet().stream().map(e -> e.getKey() + "=" + e.getValue()).toList());

        repo.setRemoteUrl("origin", "https://example.com/group/moved.git");
        assertEquals("https://example.com/group/moved.git", repo.getRemoteUrls().get("origin"));

        repo.removeRemote("backup");
        assertEquals(List.of("origin"), List.copyOf(repo.getRemoteUrls().keySet()));
    }

    /** @return Tags on the bare 'remote' */
    private List<String> getRemoteTags() throws Exception
    {
        return Git.lsRemoteRepository()
                  .setRemote(tmp.resolve("remote.git").toUri().toString())
                  .setTags(true)
                  .call()
                  .stream()
                  .map(ref -> ref.getName())
                  .filter(name -> ! name.endsWith("^{}"))
                  .toList();
    }

    // --- Serialization --------------------------------------------------------------

    @Test
    public void testConcurrentCallsAreSerialized() throws Exception
    {
        write("a.txt", "0\n");
        commitAll("base");

        // Status refresh and commits from several threads would collide on the index lock
        // unless the calls are serialized
        final List<Thread> threads = new java.util.ArrayList<>();
        final List<Throwable> errors = java.util.Collections.synchronizedList(new java.util.ArrayList<>());
        for (int i=0; i<4; ++i)
        {
            final int index = i;
            threads.add(new Thread(() ->
            {
                try
                {
                    for (int j=0; j<10; ++j)
                    {
                        final String file = "t" + index + "_" + j + ".txt";
                        synchronized (GitRepositoryTest.this)
                        {
                            write(file, file);
                        }
                        repo.getStatus();
                        repo.stage(List.of(file));
                        repo.getStatus();
                    }
                }
                catch (Throwable ex)
                {
                    errors.add(ex);
                }
            }));
        }
        threads.forEach(Thread::start);
        for (Thread thread : threads)
            thread.join();
        assertEquals(List.of(), errors);
        assertEquals(40, repo.getStatus().staged().size());
        repo.commit("many files", false);
        assertEquals(List.of(), repo.getStatus().staged());
    }
}
