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

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.StoredConfig;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.util.FS;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.phoebus.applications.git.GitRepository.ConfigEntry;
import org.phoebus.applications.git.GitRepository.ReflogInfo;
import org.phoebus.applications.git.GitRepository.ResetMode;

/** Tests of reflog, configuration, operations on several commits */
@SuppressWarnings("nls")
public class ReflogConfigTest
{
    @TempDir
    Path tmp;

    private File dir;
    private Git git;
    private GitRepository repo;
    private File original_home;

    @BeforeEach
    public void setup() throws Exception
    {
        // Don't touch the real ~/.gitconfig
        original_home = FS.DETECTED.userHome();
        final File home = tmp.resolve("home").toFile();
        home.mkdirs();
        FS.DETECTED.setUserHome(home);

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
        FS.DETECTED.setUserHome(original_home);
    }

    private String commit(final String file, final String message) throws Exception
    {
        Files.writeString(dir.toPath().resolve(file), message + "\n");
        git.add().addFilepattern(file).call();
        return git.commit().setMessage(message).call().name();
    }

    private List<String> log() throws Exception
    {
        final List<String> result = new ArrayList<>();
        for (RevCommit commit : git.log().call())
            result.add(commit.getShortMessage());
        return result;
    }

    @Test
    public void testReflog() throws Exception
    {
        assertTrue(repo.getReflog(10).isEmpty());
        commit("a.txt", "one");
        final String lost = commit("b.txt", "two");
        repo.reset(git.getRepository().resolve("HEAD~1").name(), ResetMode.HARD);

        final List<ReflogInfo> reflog = repo.getReflog(10);
        assertEquals(3, reflog.size());
        // JGit writes "<id>: updating HEAD" for a reset
        assertEquals(git.getRepository().resolve("HEAD").name(), reflog.get(0).id());
        assertEquals(lost, reflog.get(0).old_id());
        assertEquals(lost, reflog.get(1).id());
        assertTrue(reflog.get(1).comment().contains("two"), reflog.get(1).comment());
        assertEquals("Tester", reflog.get(1).who());
        assertEquals(1, repo.getReflog(1).size());
        // Lost commit can be found and restored from the reflog
        assertFalse(log().contains("two"));
        repo.createBranch("recovered", reflog.get(1).id(), false);
        assertTrue(repo.getState(10, true).log().stream().anyMatch(c -> c.message().equals("two")));
        assertEquals(repo.getState(10, true).reflog(), repo.getReflog(GitRepository.MAX_REFLOG));
    }

    @Test
    public void testRepositoryConfig() throws Exception
    {
        // Comment in the file must remain
        final Path file = dir.toPath().resolve(".git/config");
        Files.writeString(file, Files.readString(file) + "# Keep this comment\n[branch \"feature/x\"]\n\tremote = origin\n");
        git.getRepository().getConfig().load();

        List<ConfigEntry> entries = repo.getConfig(false);
        assertTrue(entries.contains(new ConfigEntry("user.name", "Tester")));
        assertTrue(entries.contains(new ConfigEntry("branch.feature/x.remote", "origin")));

        final List<ConfigEntry> update = new ArrayList<>(entries);
        update.replaceAll(e -> e.key().equals("user.name") ? new ConfigEntry("user.name", "New Name") : e);
        update.removeIf(e -> e.key().equals("branch.feature/x.remote"));
        update.add(new ConfigEntry("pull.rebase", "true"));
        update.add(new ConfigEntry("remote.origin.fetch", "+refs/heads/a:refs/remotes/origin/a"));
        update.add(new ConfigEntry("remote.origin.fetch", "+refs/heads/b:refs/remotes/origin/b"));
        repo.setConfig(false, update);

        final String text = Files.readString(file);
        assertTrue(text.contains("# Keep this comment"), text);
        entries = repo.getConfig(false);
        assertTrue(entries.contains(new ConfigEntry("user.name", "New Name")));
        assertTrue(entries.contains(new ConfigEntry("pull.rebase", "true")));
        assertFalse(entries.stream().anyMatch(e -> e.key().startsWith("branch.feature")));
        assertEquals(2, entries.stream().filter(e -> e.key().equals("remote.origin.fetch")).count());
        // Repository uses the new setting, e.g. as commit author
        commit("a.txt", "by new name");
        assertEquals("New Name", git.log().call().iterator().next().getAuthorIdent().getName());

        update.add(new ConfigEntry("nodot", "x"));
        assertThrows(Exception.class, () -> repo.setConfig(false, update));
    }

    @Test
    public void testUserConfig() throws Exception
    {
        final File user_file = GitRepository.getUserConfigFile();
        assertTrue(user_file.getPath().startsWith(tmp.toString()), user_file.getPath());
        assertTrue(repo.getConfig(true).isEmpty());
        repo.setConfig(true, List.of(new ConfigEntry("user.name", "Global"), new ConfigEntry("core.autocrlf", "input")));
        assertTrue(Files.readString(user_file.toPath()).contains("name = Global"));
        assertEquals(2, repo.getConfig(true).size());
        // Repository settings are separate
        assertFalse(repo.getConfig(false).contains(new ConfigEntry("user.name", "Global")));
    }

    @Test
    public void testSeveralCommits() throws Exception
    {
        final String base = commit("base.txt", "base");
        git.checkout().setCreateBranch(true).setName("side").call();
        final String c1 = commit("c1.txt", "c1");
        final String c2 = commit("c2.txt", "c2");
        final String c3 = commit("c3.txt", "c3");
        git.checkout().setName("master").call();
        assertEquals(List.of(c1, c2, c3), repo.sortOldestFirst(List.of(c3, c1, c2)));

        // Cherry-pick c3 and c1 in the order of the history
        assertFalse(repo.cherryPick(List.of(c3, c1)));
        assertEquals(List.of("c3", "c1", "base"), log());
        // Revert both, newest first
        final List<String> picked = new ArrayList<>();
        for (RevCommit commit : git.log().setMaxCount(2).call())
            picked.add(commit.name());
        assertFalse(repo.revert(picked));
        assertEquals(3, log().stream().filter(m -> m.startsWith("Revert")).count() + 1);
        assertFalse(new File(dir, "c1.txt").exists());
        assertFalse(new File(dir, "c3.txt").exists());
        assertTrue(base.length() > 0);
    }
}
