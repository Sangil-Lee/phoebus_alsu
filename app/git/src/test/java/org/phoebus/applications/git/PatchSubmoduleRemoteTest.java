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
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.lib.StoredConfig;
import org.eclipse.jgit.submodule.SubmoduleWalk;
import org.eclipse.jgit.transport.TagOpt;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.phoebus.applications.git.GitRepository.ChangeType;
import org.phoebus.applications.git.GitRepository.CommitFile;
import org.phoebus.applications.git.GitRepository.RemoteSettings;
import org.phoebus.applications.git.GitRepository.SubmoduleInfo;
import org.phoebus.applications.git.GitRepository.SubmoduleState;

/** Tests of patches, submodules, remote settings */
@SuppressWarnings("nls")
public class PatchSubmoduleRemoteTest
{
    @TempDir
    Path tmp;

    private static void configureUser(final Repository repository) throws Exception
    {
        final StoredConfig config = repository.getConfig();
        config.setString("user", null, "name", "Tester");
        config.setString("user", null, "email", "tester@example.com");
        config.save();
    }

    private static Git init(final File dir) throws Exception
    {
        final Git git = Git.init().setDirectory(dir).setInitialBranch("master").call();
        configureUser(git.getRepository());
        return git;
    }

    private static String commit(final Git git, final String message) throws Exception
    {
        git.add().addFilepattern(".").call();
        git.add().addFilepattern(".").setUpdate(true).call();
        return git.commit().setMessage(message).call().name();
    }

    private static void write(final File dir, final String path, final String text) throws Exception
    {
        final Path file = dir.toPath().resolve(path);
        Files.createDirectories(file.getParent());
        Files.writeString(file, text);
    }

    private static String run(final File dir, final String... command) throws Exception
    {
        final Process process = new ProcessBuilder(command).directory(dir).redirectErrorStream(true).start();
        final String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(0, process.waitFor(), output);
        return output;
    }

    @Test
    public void testPatch() throws Exception
    {
        final File dir = tmp.resolve("repo").toFile();
        try (Git git = init(dir))
        {
            final StringBuilder lines = new StringBuilder();
            for (int i=1; i<=10; ++i)
                lines.append("line ").append(i).append('\n');
            write(dir, "a.txt", lines.toString());
            write(dir, "old.txt", lines.toString());
            write(dir, "gone.txt", "gone\n");
            commit(git, "base");
            final File other = tmp.resolve("other").toFile();
            Git.cloneRepository().setURI(dir.toURI().toString()).setDirectory(other).call().close();

            write(dir, "a.txt", lines.toString().replace("line 5", "line five"));
            write(dir, "new/b.txt", "new file\n");
            final String first = commit(git, "Change a, add b\n\nWith a longer description");
            Files.move(dir.toPath().resolve("old.txt"), dir.toPath().resolve("renamed.txt"));
            Files.delete(dir.toPath().resolve("gone.txt"));
            final String second = commit(git, "Rename and delete");

            try (GitRepository repo = new GitRepository(dir))
            {
                final String patch = repo.formatPatch(List.of(second, first));
                assertTrue(patch.startsWith("From " + first), patch);
                assertTrue(patch.contains("Subject: [PATCH 1/2] Change a, add b\n\nWith a longer description\n---\n"), patch);
                assertTrue(patch.contains("Subject: [PATCH 2/2] Rename and delete"), patch);
                assertTrue(patch.contains("rename from old.txt"), patch);

                final List<CommitFile> files = GitRepository.getPatchFiles(patch.getBytes(StandardCharsets.UTF_8));
                assertEquals(List.of(new CommitFile(ChangeType.MODIFIED, "a.txt", null),
                                     new CommitFile(ChangeType.ADDED, "new/b.txt", null),
                                     new CommitFile(ChangeType.DELETED, "gone.txt", null),
                                     new CommitFile(ChangeType.RENAMED, "renamed.txt", "old.txt")), files);

                // Apply with JGit to the other clone
                try (GitRepository other_repo = new GitRepository(other))
                {
                    final List<String> updated = other_repo.applyPatch(patch.getBytes(StandardCharsets.UTF_8));
                    assertTrue(updated.containsAll(List.of("a.txt", "new/b.txt", "renamed.txt")), updated.toString());
                    assertEquals(lines.toString().replace("line 5", "line five"), Files.readString(other.toPath().resolve("a.txt")));
                    assertTrue(new File(other, "renamed.txt").exists());
                    assertFalse(new File(other, "old.txt").exists());
                    assertFalse(new File(other, "gone.txt").exists());
                    // Applying again fails: Files already changed
                    assertThrows(Exception.class, () -> other_repo.applyPatch(patch.getBytes(StandardCharsets.UTF_8)));
                }

                // 'git am' accepts the format, keeping message and author
                final File third = tmp.resolve("third").toFile();
                Git.cloneRepository().setURI(dir.toURI().toString()).setBranch("master").setDirectory(third).call().close();
                run(third, "git", "reset", "-q", "--hard", "HEAD~2");
                final Path file = tmp.resolve("changes.patch");
                Files.writeString(file, patch);
                run(third, "git", "-c", "user.name=X", "-c", "user.email=x@x", "am", "-q", file.toString());
                assertEquals("Rename and delete\nChange a, add b\nbase", run(third, "git", "log", "--format=%s").trim());
                assertEquals("Tester", run(third, "git", "log", "-1", "--format=%an").trim());
                assertEquals("", run(third, "git", "diff", "master", "origin/master", "--stat").trim());
            }
        }
    }

    @Test
    public void testRemoteSettings() throws Exception
    {
        final File remote_dir = tmp.resolve("remote").toFile();
        final File dir = tmp.resolve("repo").toFile();
        try (Git remote = init(remote_dir))
        {
            write(remote_dir, "a.txt", "a\n");
            commit(remote, "base");
            remote.branchCreate().setName("feature").call();
            remote.tag().setName("v1").call();
            Git.cloneRepository().setURI(remote_dir.toURI().toString()).setDirectory(dir).setNoTags().call().close();
            try (GitRepository repo = new GitRepository(dir))
            {
                RemoteSettings settings = repo.getRemoteSettings("origin");
                assertEquals(List.of("+refs/heads/*:refs/remotes/origin/*"), settings.fetch_specs());
                assertEquals("", settings.push_url());
                assertTrue(settings.prune());

                // Don't prune, push URL, no tags
                repo.setRemoteSettings(new RemoteSettings("origin", settings.fetch_url(), "file:///push/elsewhere",
                                                          settings.fetch_specs(), List.of("refs/heads/master:refs/heads/master"),
                                                          false, TagOpt.NO_TAGS));
                settings = repo.getRemoteSettings("origin");
                assertEquals("file:///push/elsewhere", settings.push_url());
                assertEquals(List.of("refs/heads/master:refs/heads/master"), settings.push_specs());
                assertFalse(settings.prune());
                assertEquals(TagOpt.NO_TAGS, settings.tags());

                remote.branchDelete().setBranchNames("feature").setForce(true).call();
                repo.fetch(null);
                assertTrue(repo.getRemoteBranches().get("origin").stream().anyMatch(b -> b.name().equals("feature")));
                assertTrue(repo.getTags().isEmpty());

                // Prune, all tags
                repo.setRemoteSettings(new RemoteSettings("origin", settings.fetch_url(), "",
                                                          settings.fetch_specs(), List.of(), true, TagOpt.FETCH_TAGS));
                repo.fetch(null);
                assertFalse(repo.getRemoteBranches().get("origin").stream().anyMatch(b -> b.name().equals("feature")));
                assertEquals(1, repo.getTags().size());
                assertEquals("", repo.getRemoteSettings("origin").push_url());
            }
        }
    }

    @Test
    public void testSubmodules() throws Exception
    {
        final File sub_dir = tmp.resolve("sub").toFile();
        final File parent_dir = tmp.resolve("parent").toFile();
        try (Git sub = init(sub_dir);
             Git parent = init(parent_dir))
        {
            write(sub_dir, "lib.py", "x = 1\n");
            final String sub_first = commit(sub, "sub first");
            write(parent_dir, "main.bob", "<display/>\n");
            commit(parent, "parent");
            parent.submoduleAdd().setURI(sub_dir.toURI().toString()).setPath("lib").call().close();
            commit(parent, "add submodule");

            // Clone without submodules: Not initialized
            final File clone = tmp.resolve("clone").toFile();
            GitRepository.cloneRepository(parent_dir.toURI().toString(), clone, false, null, null);
            try (GitRepository repo = new GitRepository(clone))
            {
                List<SubmoduleInfo> modules = repo.getSubmodules();
                assertEquals(1, modules.size());
                assertEquals("lib", modules.get(0).path());
                assertEquals(SubmoduleState.UNINITIALIZED, modules.get(0).state());
                assertEquals(sub_first, modules.get(0).recorded());
                assertNull(modules.get(0).actual());
                assertTrue(modules.get(0).url().contains("sub"), modules.get(0).url());

                repo.updateSubmodules(List.of(), null);
                modules = repo.getSubmodules();
                assertEquals(SubmoduleState.OK, modules.get(0).state());
                assertTrue(new File(clone, "lib/lib.py").exists());

                // Submodule checked out at other commit
                write(sub_dir, "lib.py", "x = 2\n");
                final String sub_second = commit(sub, "sub second");
                try (Repository clone_repo = Git.open(clone).getRepository();
                     Git lib = Git.wrap(SubmoduleWalk.getSubmoduleRepository(clone_repo, "lib")))
                {
                    lib.fetch().call();
                    lib.checkout().setName(sub_second).call();
                }
                modules = repo.getSubmodules();
                assertEquals(SubmoduleState.DIFFERENT, modules.get(0).state());
                assertEquals(sub_second, modules.get(0).actual());
                // Staging the submodule records the new commit
                repo.stage(List.of("lib"));
                repo.commit("update lib", false);
                assertEquals(SubmoduleState.OK, repo.getSubmodules().get(0).state());
                assertEquals(sub_second, repo.getSubmodules().get(0).recorded());
            }

            // 'git submodule deinit' leaves the repository in .git/modules
            run(clone, "git", "submodule", "deinit", "-q", "-f", "lib");
            assertFalse(new File(clone, "lib/lib.py").exists());
            try (GitRepository repo = new GitRepository(clone))
            {
                assertEquals(SubmoduleState.UNINITIALIZED, repo.getSubmodules().get(0).state());
                repo.updateSubmodules(List.of("lib"), null);
                assertEquals(SubmoduleState.OK, repo.getSubmodules().get(0).state());
                assertEquals("x = 2\n", Files.readString(clone.toPath().resolve("lib/lib.py")));
            }
            assertEquals("", run(clone, "git", "status", "--short").trim());

            // Clone with submodules
            final File full = tmp.resolve("full").toFile();
            GitRepository.cloneRepository(parent_dir.toURI().toString(), full, true, null, null);
            assertTrue(new File(full, "lib/lib.py").exists());
        }
    }
}
