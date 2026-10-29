package org.phoebus.applications.git;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.lib.RepositoryState;
import org.eclipse.jgit.lib.StoredConfig;
import org.eclipse.jgit.revwalk.RevCommit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.phoebus.applications.git.GitRepository.CommitInfo;
import org.phoebus.applications.git.GitRepository.RebaseAction;
import org.phoebus.applications.git.GitRepository.RebaseStep;

/** Tests of interactive rebase */
@SuppressWarnings("nls")
public class InteractiveRebaseTest
{
    @TempDir
    Path tmp;

    private File dir;
    private Git git;
    private GitRepository repo;
    private final List<String> commits = new ArrayList<>();

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
        // base, then one file per commit
        commit("base.txt", "base", "base");
        for (int i=1; i<=4; ++i)
            commit("f" + i + ".txt", "file " + i, "commit " + i);
    }

    @AfterEach
    public void cleanup()
    {
        repo.close();
        git.close();
    }

    private void commit(final String file, final String text, final String message) throws Exception
    {
        Files.writeString(dir.toPath().resolve(file), text + "\n");
        git.add().addFilepattern(file).call();
        commits.add(git.commit().setMessage(message).call().name());
    }

    /** @return Messages from HEAD back to base, oldest first */
    private List<String> log() throws Exception
    {
        final List<String> result = new ArrayList<>();
        for (RevCommit commit : git.log().call())
            result.add(0, commit.getFullMessage().trim());
        return result;
    }

    private List<RebaseStep> steps(final RebaseAction... actions) throws Exception
    {
        final List<CommitInfo> after = repo.getCommitsAfter(commits.get(0));
        final List<RebaseStep> steps = new ArrayList<>();
        for (int i=0; i<actions.length; ++i)
            steps.add(new RebaseStep(actions[i], after.get(i)));
        return steps;
    }

    @Test
    public void testCommitsAfter() throws Exception
    {
        final List<CommitInfo> after = repo.getCommitsAfter(commits.get(0));
        assertEquals(List.of("commit 1", "commit 2", "commit 3", "commit 4"), after.stream().map(CommitInfo::message).toList());
        assertEquals(commits.get(1), after.get(0).id());

        // Pushed: Commits on a remote branch
        final RefUpdate update = git.getRepository().updateRef("refs/remotes/origin/master");
        update.setNewObjectId(ObjectId.fromString(commits.get(2)));
        update.forceUpdate();
        assertEquals(Set.of(commits.get(1), commits.get(2)),
                     repo.getPushedCommits(after.stream().map(CommitInfo::id).toList()));
    }

    @Test
    public void testReorderDropPick() throws Exception
    {
        final List<RebaseStep> steps = steps(RebaseAction.PICK, RebaseAction.DROP, RebaseAction.PICK, RebaseAction.PICK);
        // Move 'commit 4' before 'commit 3'
        steps.add(2, steps.remove(3));
        assertFalse(repo.rebaseInteractive(commits.get(0), steps));
        assertEquals(List.of("base", "commit 1", "commit 4", "commit 3"), log());
        assertFalse(new File(dir, "f2.txt").exists());
        assertTrue(new File(dir, "f4.txt").exists());
        assertEquals(RepositoryState.SAFE, git.getRepository().getRepositoryState());
    }

    @Test
    public void testSquashFixupReword() throws Exception
    {
        final List<String> edited = new ArrayList<>();
        repo.setRebaseMessageEditor(message ->
        {
            edited.add(message);
            return message.contains("commit 2") ? "1 and 2 combined" : "reworded 4";
        });
        assertFalse(repo.rebaseInteractive(commits.get(0), steps(RebaseAction.PICK, RebaseAction.SQUASH,
                                                                 RebaseAction.FIXUP, RebaseAction.REWORD)));
        assertEquals(List.of("base", "1 and 2 combined", "reworded 4"), log());
        assertEquals(2, edited.size());
        assertTrue(edited.get(0).contains("commit 1")  &&  edited.get(0).contains("commit 2"), edited.get(0));
        for (int i=1; i<=4; ++i)
            assertTrue(new File(dir, "f" + i + ".txt").exists());
    }

    @Test
    public void testEdit() throws Exception
    {
        repo.setRebaseMessageEditor(message -> message);
        // Stop at commit 2 to amend it
        assertTrue(repo.rebaseInteractive(commits.get(0), steps(RebaseAction.PICK, RebaseAction.EDIT,
                                                                RebaseAction.PICK, RebaseAction.PICK)));
        assertTrue(git.getRepository().getRepositoryState().isRebasing());
        assertEquals(List.of("base", "commit 1", "commit 2"), log());
        Files.writeString(dir.toPath().resolve("f2.txt"), "amended\n");
        repo.stage(List.of("f2.txt"));
        repo.commit("commit 2 amended", true);
        assertFalse(repo.continueRebase());
        assertEquals(List.of("base", "commit 1", "commit 2 amended", "commit 3", "commit 4"), log());
        assertEquals("amended\n", Files.readString(dir.toPath().resolve("f2.txt")));
    }

    @Test
    public void testConflict() throws Exception
    {
        // Commit 5 changes f4.txt, so moving it before commit 4 conflicts
        commit("f4.txt", "changed", "commit 5");
        final List<CommitInfo> after = repo.getCommitsAfter(commits.get(0));
        final List<RebaseStep> steps = new ArrayList<>();
        for (int i : new int[] { 0, 1, 2, 4, 3 })
            steps.add(new RebaseStep(RebaseAction.PICK, after.get(i)));
        assertTrue(repo.rebaseInteractive(commits.get(0), steps));
        assertTrue(repo.getStatus().rebasing());
        repo.abortRebase();
        assertEquals(List.of("base", "commit 1", "commit 2", "commit 3", "commit 4", "commit 5"), log());
    }

    @Test
    public void testMergeNotSupported() throws Exception
    {
        git.checkout().setCreateBranch(true).setName("side").setStartPoint(commits.get(1)).call();
        commit("side.txt", "side", "side");
        git.checkout().setName("master").call();
        git.merge().include(git.getRepository().resolve("side")).setMessage("merge").call();
        assertThrows(Exception.class, () -> repo.getCommitsAfter(commits.get(0)));
        // Commit that's not on the branch
        assertThrows(Exception.class, () -> repo.getCommitsAfter(git.getRepository().resolve("side").name()));
    }
}
