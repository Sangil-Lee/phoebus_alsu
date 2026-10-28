package org.phoebus.applications.git;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Tests of {@link RepositoryWatcher} and the watched directories */
@SuppressWarnings("nls")
public class RepositoryWatcherTest
{
    @TempDir
    Path tmp;

    private Path dir;
    private Git git;
    private GitRepository repo;
    private RepositoryWatcher watcher;
    private final AtomicInteger changes = new AtomicInteger();

    @BeforeEach
    public void setup() throws Exception
    {
        dir = tmp.resolve("repo");
        git = Git.init().setDirectory(dir.toFile()).setInitialBranch("master").call();
        git.getRepository().getConfig().setString("user", null, "name", "Tester");
        git.getRepository().getConfig().setString("user", null, "email", "tester@example.com");
        Files.writeString(dir.resolve(".gitignore"), "target/\n");
        Files.createDirectories(dir.resolve("src/main"));
        Files.writeString(dir.resolve("src/main/a.txt"), "1\n");
        Files.createDirectories(dir.resolve("target/classes"));
        Files.writeString(dir.resolve("target/classes/out.txt"), "build output\n");
        git.add().addFilepattern(".").call();
        git.commit().setMessage("base").call();
        repo = new GitRepository(dir.toFile());
    }

    @AfterEach
    public void cleanup()
    {
        if (watcher != null)
            watcher.close();
        repo.close();
        git.close();
    }

    private void startWatcher() throws Exception
    {
        watcher = new RepositoryWatcher(() -> repo.getWatchDirectories(RepositoryWatcher.MAX_DIRECTORIES),
                                        changes::incrementAndGet);
    }

    /** @return <code>true</code> if the number of changes reached the expected count within the timeout */
    private boolean awaitChanges(final int expected, final long timeout_ms) throws Exception
    {
        final long end = System.currentTimeMillis() + timeout_ms;
        while (System.currentTimeMillis() < end)
        {
            if (changes.get() >= expected)
                return true;
            Thread.sleep(50);
        }
        return changes.get() >= expected;
    }

    @Test
    public void testWatchDirectories() throws Exception
    {
        final List<String> dirs = repo.getWatchDirectories(100).stream()
                                      .map(f -> dir.relativize(f.toPath()).toString().replace(File.separatorChar, '/'))
                                      .toList();
        assertTrue(dirs.contains(""));
        assertTrue(dirs.contains("src"));
        assertTrue(dirs.contains("src/main"));
        assertTrue(dirs.contains(".git"));
        assertTrue(dirs.contains(".git/refs/heads"));
        // Ignored build output is not watched
        assertFalse(dirs.stream().anyMatch(d -> d.startsWith("target")));
        // Limit for the work tree directories
        assertEquals(2, repo.getWatchDirectories(2).stream()
                            .filter(f -> ! f.getPath().contains(".git"))
                            .count());
    }

    @Test
    public void testWorkTreeChange() throws Exception
    {
        startWatcher();
        Files.writeString(dir.resolve("src/main/a.txt"), "2\n");
        assertTrue(awaitChanges(1, 5000));
        // Several quick changes result in one callback
        final int before = changes.get();
        for (int i=0; i<5; ++i)
            Files.writeString(dir.resolve("src/main/a.txt"), "change " + i + "\n");
        assertTrue(awaitChanges(before + 1, 5000));
        Thread.sleep(1000);
        assertEquals(before + 1, changes.get());
    }

    @Test
    public void testIgnoredChange() throws Exception
    {
        startWatcher();
        Files.writeString(dir.resolve("target/classes/out.txt"), "new build output\n");
        assertFalse(awaitChanges(1, 1500));
    }

    @Test
    public void testRepositoryChange() throws Exception
    {
        startWatcher();
        // Branch created by another tool changes .git/refs
        git.branchCreate().setName("feature").call();
        assertTrue(awaitChanges(1, 5000));
    }

    @Test
    public void testNewDirectory() throws Exception
    {
        startWatcher();
        final int watched = watcher.getWatchCount();
        Files.createDirectories(dir.resolve("src/new"));
        assertTrue(awaitChanges(1, 5000));
        // New directory is watched after the change was reported
        Thread.sleep(200);
        assertEquals(watched + 1, watcher.getWatchCount());
        Files.writeString(dir.resolve("src/new/b.txt"), "b\n");
        assertTrue(awaitChanges(2, 5000));
    }
}
